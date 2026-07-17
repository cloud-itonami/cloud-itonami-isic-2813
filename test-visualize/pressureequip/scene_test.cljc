(ns pressureequip.scene-test
  "OPT-IN 3D-visualization E2E gate (not part of `clojure -M:test`'s default
  \"test\" dir — see deps.edn's `:visualize` alias comment). Verifies TWO
  things about `pressureequip.scene/unit->render-ir`:

    1. Structural conformance to `kami.webgpu.ir`'s v1 render-IR contract,
       checked with the REAL `kami.webgpu.ir/valid?` validator (not a
       hand-rolled shape check).
    2. The scene actually DRAWS: baked with the REAL `kami.webgpu.geometry`
       mesh generators (`geom/box` / `geom/cylinder` — the same .cljc the
       browser executor consumes) and rendered in a REAL headless WebGL2
       Chromium via `kami.playwright/eval-page`, then pixel-verified with
       `readPixels`. This mirrors kotoba-lang/webgpu's own
       `test/playwright_mesh_test.clj` end-to-end (perspective/lookAt/
       translate textbook matrices, GLSL mesh shaders, drawElements,
       readPixels) — no new rendering methodology invented here, just
       extended to loop over N instances (this scene has 5) and honour each
       instance's `:pos`/`:yaw` via an added `rotateY`."
  (:require [clojure.test :refer [deftest is]]
            [kami.playwright :as pw]
            [kami.webgpu.geometry :as geom]
            [kami.webgpu.ir :as ir]
            [cheshire.core :as json]
            [pressureequip.facts :as facts]
            [pressureequip.scene :as scene]))

(defn- bake-mesh
  "instance -> a {:positions :normals :uvs :indices} mesh via the REAL
  kami.webgpu.geometry generators. `:size` follows the `[w h d]` /
  `kami.webgpu.ir/instance-size` convention; for `:cylinder` the footprint
  diameter lives in `w` (== `d`, a cylinder's plan-view bbox is square)."
  [{:keys [geo size]}]
  (let [[w h d] size]
    (case geo
      :cylinder (geom/cylinder (/ w 2.0) h 24)
      (geom/box w h d))))

(def ^:private mesh-vertex-shader
  ;; identical to kotoba-lang/webgpu's test/playwright_mesh_test.clj
  "#version 300 es
precision highp float;
layout(location=0) in vec3 a_position;
layout(location=1) in vec3 a_normal;
uniform mat4 u_mvp;
out vec3 v_normal;
void main(){ gl_Position=u_mvp*vec4(a_position,1.0); v_normal=a_normal; }")

(def ^:private mesh-fragment-shader
  ;; identical to kotoba-lang/webgpu's test/playwright_mesh_test.clj
  "#version 300 es
precision highp float;
in vec3 v_normal;
uniform vec3 u_color;
uniform vec2 u_material;
out vec4 out_color;
void main(){ vec3 n=normalize(v_normal); vec3 light=normalize(vec3(0.4,0.8,0.6)); float ndl=max(dot(n,light),0.0); float l=0.25+0.75*ndl; float metallic=clamp(u_material.x,0.0,1.0); float roughness=clamp(u_material.y,0.04,1.0); vec3 h=normalize(light+vec3(0.0,0.0,1.0)); float spec=pow(max(dot(n,h),0.0),mix(128.0,2.0,roughness))*ndl; vec3 f0=mix(vec3(0.04),u_color,metallic); out_color=vec4(u_color*l*(1.0-metallic*0.45)+f0*spec,1.0); }")

(defn- the-unit []
  (facts/unit-type-by-id :unit/industrial-refrigeration-compressor))

(deftest unit-render-ir-conforms-to-v1-shape
  (let [scene-ir (scene/unit->render-ir (the-unit))]
    (is (ir/valid? scene-ir)
        "pressureequip.scene/unit->render-ir output conforms to kami.webgpu.ir's v1 render-IR shape (checked with the real ir/valid?, not a hand-rolled check)")
    (is (= 5 (count (:instances scene-ir)))
        "housing + compressor-body + 2 condenser fans + control panel")
    (is (= #{:box :cylinder} (set (map :geo (:instances scene-ir))))
        "only the two parametric primitive kinds this scene uses")))

(deftest compressor-unit-renders-in-real-headless-webgl2-browser
  (let [scene-ir (scene/unit->render-ir (the-unit))
        instances (:instances scene-ir)
        {:keys [eye target]} (:globals scene-ir)
        meshes (mapv bake-mesh instances)
        width 800 height 600
        fov 55 near 0.5 far 60.0
        js (str
             "const meshes=" (json/generate-string meshes) ";"
             "const insts=" (json/generate-string instances) ";"
             "const eye=" (json/generate-string eye) ", target=" (json/generate-string target) ";"
             "const fov=" fov ", near=" near ", far=" far ", W=" width ", H=" height ";"
             ;; -- matrix helpers: perspective/lookAt/translate are the SAME textbook
             ;; formulas as playwright_mesh_test.clj (WebGL's -1..1 clip-space depth
             ;; convention); rotateY is the one addition, to honour each instance's :yaw.
             "function m4(){return new Float32Array(16);}"
             "function m4mul(a,b){const o=m4();for(let c=0;c<4;c++)for(let r=0;r<4;r++){o[c*4+r]=a[r]*b[c*4+0]+a[r+4]*b[c*4+1]+a[r+8]*b[c*4+2]+a[r+12]*b[c*4+3];}return o;}"
             "function perspective(fovy,aspect,near,far){const f=1.0/Math.tan(fovy/2.0),nf=1.0/(near-far),o=m4();o[0]=f/aspect;o[5]=f;o[10]=(far+near)*nf;o[14]=2.0*far*near*nf;o[11]=-1.0;return o;}"
             "function vsub(a,b){return [a[0]-b[0],a[1]-b[1],a[2]-b[2]];} function vnorm(v){const l=Math.hypot(...v);return [v[0]/l,v[1]/l,v[2]/l];}"
             "function vcross(a,b){return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];} function vdot(a,b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}"
             "function lookAt(eye,center,up){const f=vnorm(vsub(center,eye)),s=vnorm(vcross(f,up)),u=vcross(s,f),o=m4();"
             "  o[0]=s[0];o[4]=s[1];o[8]=s[2];o[1]=u[0];o[5]=u[1];o[9]=u[2];o[2]=-f[0];o[6]=-f[1];o[10]=-f[2];"
             "  o[12]=-vdot(s,eye);o[13]=-vdot(u,eye);o[14]=vdot(f,eye);o[15]=1.0;return o;}"
             "function translate(p){const o=m4();o[0]=1;o[5]=1;o[10]=1;o[15]=1;o[12]=p[0];o[13]=p[1];o[14]=p[2];return o;}"
             "function rotateY(t){const o=m4();const cs=Math.cos(t),sn=Math.sin(t);o[0]=cs;o[2]=-sn;o[5]=1;o[8]=sn;o[10]=cs;o[15]=1;return o;}"
             "const proj=perspective(fov*Math.PI/180,W/Math.max(1,H),near,far);"
             "const vp=m4mul(proj, lookAt(eye,target,[0,1,0]));"
             "const cv=document.createElement('canvas');cv.width=W;cv.height=H;"
             "const gl=cv.getContext('webgl2',{preserveDrawingBuffer:true});"
             "function c(t,s){const x=gl.createShader(t);gl.shaderSource(x,s);gl.compileShader(x);"
             "  if(!gl.getShaderParameter(x,gl.COMPILE_STATUS)) throw new Error('compile: '+gl.getShaderInfoLog(x));return x;}"
             "const p=gl.createProgram();gl.attachShader(p,c(gl.VERTEX_SHADER," (json/generate-string mesh-vertex-shader) "));"
             "gl.attachShader(p,c(gl.FRAGMENT_SHADER," (json/generate-string mesh-fragment-shader) "));gl.linkProgram(p);"
             "if(!gl.getProgramParameter(p,gl.LINK_STATUS)) throw new Error('link: '+gl.getProgramInfoLog(p));"
             "gl.enable(gl.DEPTH_TEST);gl.viewport(0,0,W,H);gl.clearColor(0.035,0.055,0.10,1.0);"
             "gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.useProgram(p);"
             "const u_mvp=gl.getUniformLocation(p,'u_mvp'),u_color=gl.getUniformLocation(p,'u_color'),u_material=gl.getUniformLocation(p,'u_material');"
             "let glErr=0;"
             "for(let i=0;i<meshes.length;i++){"
             "  const mesh=meshes[i], inst=insts[i];"
             "  const vao=gl.createVertexArray();gl.bindVertexArray(vao);"
             "  const vbuf=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vbuf);"
             "  const verts=[];for(let j=0;j<mesh.positions.length;j++){verts.push(...mesh.positions[j],...mesh.normals[j]);}"
             "  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(verts),gl.STATIC_DRAW);"
             "  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,24,0);"
             "  gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,3,gl.FLOAT,false,24,12);"
             "  const ibuf=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ibuf);"
             "  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint32Array(mesh.indices),gl.STATIC_DRAW);"
             "  const model=m4mul(translate(inst.pos), rotateY(inst.yaw||0));"
             "  const mvp=m4mul(vp, model);"
             "  gl.uniformMatrix4fv(u_mvp,false,mvp);"
             "  gl.uniform3f(u_color, inst.color[0], inst.color[1], inst.color[2]);"
             "  gl.uniform2f(u_material, inst.metallic||0, inst.roughness||0.6);"
             "  gl.bindVertexArray(vao);gl.drawElements(gl.TRIANGLES,mesh.indices.length,gl.UNSIGNED_INT,0);"
             "  glErr = glErr || gl.getError();"
             "}"
             ;; -- verification: PBR-lit surfaces don't hold a stable albedo colour
             ;; frame-wide (diffuse falloff + specular vary per-fragment), so — like
             ;; playwright_mesh_test.clj's own center-vs-corner check — sample each
             ;; instance at the exact pixel its own world position projects to (via
             ;; the SAME vp/model matrices the draw loop used), and prove that pixel
             ;; differs from a known-empty background corner. Real analytic
             ;; projection, not frame-wide colour histogramming.
             "function project(mvp,pos){"
             "  const x=pos[0],y=pos[1],z=pos[2];"
             "  const cx=mvp[0]*x+mvp[4]*y+mvp[8]*z+mvp[12], cy=mvp[1]*x+mvp[5]*y+mvp[9]*z+mvp[13],"
             "        cw=mvp[3]*x+mvp[7]*y+mvp[11]*z+mvp[15];"
             "  const ndcx=cx/cw, ndcy=cy/cw;"
             "  return [Math.round((ndcx*0.5+0.5)*W), Math.round((1-(ndcy*0.5+0.5))*H)];"
             "}"
             "function px(x,y){const cx=Math.max(0,Math.min(W-1,x)),cy=Math.max(0,Math.min(H-1,y));"
             "  const b=new Uint8Array(4);gl.readPixels(cx,H-1-cy,1,1,gl.RGBA,gl.UNSIGNED_BYTE,b);return Array.from(b);}"
             "const corner=px(5,5);"
             "const samples=insts.map((inst)=>{"
             "  const model=m4mul(translate(inst.pos), rotateY(inst.yaw||0));"
             "  const [sx,sy]=project(m4mul(vp,model), [0,0,0]);"
             "  return {geo: inst.geo, xy: [sx,sy], rgba: px(sx,sy)};"
             "});"
             "let bg=0;const buf=new Uint8Array(W*H*4);gl.readPixels(0,0,W,H,gl.RGBA,gl.UNSIGNED_BYTE,buf);"
             "for(let k=0;k<buf.length;k+=4){if(buf[k]===corner[0]&&buf[k+1]===corner[1]&&buf[k+2]===corner[2])bg++;}"
             "return {glError: glErr, corner, samples, bg, total: W*H};")
        r (pw/eval-page js)
        far-enough? (fn [[r1 g1 b1] [r2 g2 b2]]
                      (> (+ (Math/abs (- r1 r2)) (Math/abs (- g1 g2)) (Math/abs (- b1 b2))) 12))]
    (println "  gl error:" (:glError r) "· corner (bg):" (:corner r) "· bg px:" (:bg r) "/" (:total r))
    (doseq [{:keys [geo xy rgba]} (:samples r)]
      (println "   " geo "@" xy "->" rgba))
    (is (zero? (:glError r)) "no GL errors during setup/draw")
    (is (> (:bg r) 1000) "background clear colour is still visible (the scene doesn't fill the whole frame)")
    (is (< (:bg r) (:total r)) "drawn geometry doesn't cover literally every pixel")
    (is (= 5 (count (:samples r))))
    (doseq [{:keys [geo rgba]} (:samples r)]
      (is (far-enough? rgba (:corner r))
          (str "a " geo " instance's own projected centre pixel is visibly drawn (differs from the empty background corner), rgba=" rgba)))))

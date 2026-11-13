/*
 * hook.js — runs inside the game via frida-gadget (see patcher/patch_apk.py).
 *
 * DIAGNOSTIC BUILD: prints the game's camera memory so we can work out how cameras
 * (loaded from .lcm = rCameraList resources) reach the screen.
 *
 * What static analysis of libGS5_Android.so already showed:
 *   - .lcm files are rCameraList; each CAMERA_INFO is sampled at a frame time through
 *     CAMERA_INFO::getPos/getTarget/getQuat/getFov(float).
 *   - uMotionCamera / uAppMotionCamera override move(), getViewMat() and
 *     applyWorldOffset(). The base uCamera::applyWorldOffset we called before touches
 *     fields (+0x50 / +0x70) that nothing reads, and uMotionCamera::applyWorldOffset
 *     returns early when this+0x148 != 0.
 *
 * OUTPUT goes to BOTH:
 *   adb logcat -s frida          (lines are tagged [freecam])
 *   <external files dir>/freecam_dump.txt   (path is printed at startup)
 */

const LIB = "libGS5_Android.so";
const LISTEN_PORT = 27042; // must match OverlayService.HOOK_PORT
const DEFAULT_PKG = "jp.co.capcom.gyakusai5en";

const CFG = {
    APPLY_OFFSET: true,   // shift .lcm cameras by the overlay joystick (patches getPos/getTarget results)
    OFFSET_STEP: 0.05,    // world units per tick per unit of joystick deflection
    DUMP_LEN: 0x180,      // bytes dumped per camera object
    FIRST_CALLS: 2,       // log first N calls of each per-frame function in detail...
    EVERY_N: 600,         // ...then every Nth call (~10s at 60fps)
    HEARTBEAT_MS: 5000,
};

const ARM64 = Process.arch === "arm64";
const SYM = {
    changeCamera: "_ZN10sAppCamera12changeCameraEP7uCamera",
    setCameraList: "_ZN13uMotionCamera13setCameraListEP11rCameraList",
    mcApply: "_ZN13uMotionCamera16applyWorldOffsetERK9MtVector3S2_",
    infoPos: "_ZNK11rCameraList11CAMERA_INFO6getPosEf",
    infoTarget: "_ZNK11rCameraList11CAMERA_INFO9getTargetEf",
    infoQuat: "_ZNK11rCameraList11CAMERA_INFO7getQuatEf",
    infoFov: "_ZNK11rCameraList11CAMERA_INFO6getFovEf",
};
// per-frame camera functions: [label, symbol]
const FRAME_FNS = [
    ["uMotionCamera::move", "_ZN13uMotionCamera4moveEv"],
    ["uAppMotionCamera::move", "_ZN16uAppMotionCamera4moveEv"],
    ["uFreeCamera::move", "_ZN11uFreeCamera4moveEv"],
    ["uCamera::move", "_ZN7uCamera4moveEv"],
    ["uCamera::moveAfter", "_ZN7uCamera9moveAfterEv"],
];
// view-matrix producers: return an MtMatrix (64 bytes) through the hidden return pointer
const VIEWMAT_FNS = [
    ["uMotionCamera::getViewMat", "_ZN13uMotionCamera10getViewMatEv"],
    ["uAppMotionCamera::getViewMat", "_ZN16uAppMotionCamera10getViewMatEv"],
    ["uFreeCamera::getViewMat", "_ZN11uFreeCamera10getViewMatEv"],
];

// ───────────────────────── logging (logcat + file) ─────────────────────────
let logFile = null;
function openLogFile() {
    let pkg = DEFAULT_PKG;
    try {
        const t = File.readAllText("/proc/self/cmdline").split("\0")[0];
        if (t && t.indexOf(".") > 0) pkg = t;
    } catch (e) { /* keep default */ }
    let mkdir = null;
    try {
        mkdir = new NativeFunction(Module.getExportByName(null, "mkdir"), "int", ["pointer", "int"]);
    } catch (e) { /* ignore */ }
    const dirs = [
        `/sdcard/Android/data/${pkg}/files`,
        `/storage/emulated/0/Android/data/${pkg}/files`,
        `/data/data/${pkg}/files`,
    ];
    for (const d of dirs) {
        try {
            if (mkdir) {
                let acc = "";
                for (const part of d.split("/").filter(Boolean)) {
                    acc += "/" + part;
                    mkdir(Memory.allocUtf8String(acc), 0o777);
                }
            }
            logFile = new File(d + "/freecam_dump.txt", "a");
            console.log(`[freecam] dump file: ${d}/freecam_dump.txt`);
            return;
        } catch (e) { /* try next */ }
    }
    console.log("[freecam] could not open a dump file; logcat only");
}

function log(msg) {
    console.log(`[freecam] ${msg}`);
    if (logFile) {
        try { logFile.write(msg + "\n"); logFile.flush(); } catch (e) { logFile = null; }
    }
}

// ───────────────────────── memory helpers ─────────────────────────
function f32txt(v) {
    if (v === 0) return "0";
    const a = Math.abs(v);
    if (!isFinite(v) || a < 1e-6 || a > 1e7) return ".";
    return String(+v.toPrecision(6));
}

function snap(p, len) {
    try { return p.readByteArray(len); } catch (e) { return null; }
}

function hex2(n) { return (n < 16 ? "0" : "") + n.toString(16); }

/** 16 bytes per line: offset, hex, and the same bytes read as 4 floats ('.' = not float-like). */
function dumpMem(p, len, label) {
    const buf = snap(p, len);
    if (!buf) { log(`${label}: <unreadable @ ${p}>`); return; }
    const u8 = new Uint8Array(buf);
    const dv = new DataView(buf);
    log(`--- ${label} @ ${p} (${len} bytes) ---`);
    for (let off = 0; off < len; off += 16) {
        let hx = "";
        for (let i = 0; i < 16 && off + i < len; i++) hx += hex2(u8[off + i]) + (i === 7 ? "  " : " ");
        const fl = [];
        for (let i = 0; i < 16 && off + i + 4 <= len; i += 4) fl.push(f32txt(dv.getFloat32(off + i, true)));
        log(`+0x${off.toString(16).padStart(3, "0")}  ${hx} | ${fl.join(" ")}`);
    }
}

function diffMem(before, after, label) {
    if (!before || !after) return;
    const a = new DataView(before), b = new DataView(after);
    const out = [];
    for (let off = 0; off + 4 <= before.byteLength; off += 4) {
        if (a.getUint32(off, true) !== b.getUint32(off, true)) {
            out.push(`+0x${off.toString(16)}: ${f32txt(a.getFloat32(off, true))} -> ${f32txt(b.getFloat32(off, true))}` +
                ` (${a.getUint32(off, true).toString(16)} -> ${b.getUint32(off, true).toString(16)})`);
        }
    }
    log(`${label}: ${out.length} word(s) changed during the call` + (out.length ? ":\n  " + out.join("\n  ") : ""));
}

// ───────────────────────── class names from vtables ─────────────────────────
let vtables = [];
function buildVtableIndex() {
    const list = [];
    for (const e of Module.enumerateExports(LIB)) {
        if (e.name.indexOf("_ZTV") === 0) {
            let nm = e.name.substring(4);
            const m = /^(\d+)(.*)$/.exec(nm);
            if (m) {
                const n = parseInt(m[1], 10);
                nm = m[2].substring(0, n) + (m[2].length > n ? "…" + m[2].substring(n) : "");
            }
            list.push({ start: e.address, name: nm });
        }
    }
    list.sort((x, y) => x.start.compare(y.start));
    vtables = list;
    log(`indexed ${vtables.length} vtables`);
}

function vtableName(vt) {
    let lo = 0, hi = vtables.length - 1, best = -1;
    while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (vtables[mid].start.compare(vt) <= 0) { best = mid; lo = mid + 1; } else hi = mid - 1;
    }
    if (best < 0) return null;
    const d = vt.sub(vtables[best].start).toInt32();
    return d >= 0 && d < 0x1000 ? vtables[best].name : null;
}

function classOf(p) {
    try { return vtableName(p.readPointer()); } catch (e) { return null; }
}

/** Dump an object and list every qword in it that points at another (named) object. */
function dumpObject(p, len, label) {
    dumpMem(p, len, `${label} [class=${classOf(p)}]`);
    const lines = [];
    for (let off = 0; off + 8 <= len; off += 8) {
        let q;
        try { q = p.add(off).readPointer(); } catch (e) { continue; }
        if (q.isNull() || q.compare(ptr(0x10000)) < 0) continue;
        const r = Process.findRangeByAddress(q);
        if (!r || r.protection.indexOf("r") < 0) continue;
        if (off === 0) { lines.push(`+0x000 -> vtable of class ${vtableName(q)}`); continue; }
        const cn = classOf(q);
        lines.push(`+0x${off.toString(16)} -> ${q}` + (cn ? `  object of class ${cn}` : "  (readable memory)"));
    }
    log(`pointers inside ${label}:\n  ` + (lines.join("\n  ") || "(none)"));
}

// ───────────────────────── state shared with the overlay ─────────────────────────
const state = { dx: 0, dy: 0, dz: 0 };       // joystick deflection (from overlay)
const offset = { x: 0, y: 0, z: 0 };         // accumulated camera shift
let activeCameraPtr = null;
const stats = {};
function bump(name) { return (stats[name] = (stats[name] || 0) + 1); }
function shouldLog(n) { return n <= CFG.FIRST_CALLS || n % CFG.EVERY_N === 0; }

function tick() {
    offset.x += state.dx * CFG.OFFSET_STEP;
    offset.y += state.dy * CFG.OFFSET_STEP;
    offset.z += state.dz * CFG.OFFSET_STEP;
    setTimeout(tick, 16);
}

function addOffsetAt(p) {
    p.writeFloat(p.readFloat() + offset.x);
    p.add(4).writeFloat(p.add(4).readFloat() + offset.y);
    p.add(8).writeFloat(p.add(8).readFloat() + offset.z);
}

// ───────────────────────── hooks ─────────────────────────
function sym(name) {
    const a = Module.findExportByName(LIB, name);
    if (!a) log(`!! symbol not found: ${name}`);
    return a;
}

function hookChangeCamera() {
    const a = sym(SYM.changeCamera);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            const cam = args[1];
            const n = bump("sAppCamera::changeCamera");
            if (cam.isNull()) { log(`changeCamera #${n}: null camera`); return; }
            activeCameraPtr = cam;
            log(`===== changeCamera #${n}: new active camera ${cam} class=${classOf(cam)} =====`);
            dumpObject(cam, CFG.DUMP_LEN, "active camera");
        },
    });
    log("hooked sAppCamera::changeCamera");
}

function hookSetCameraList() {
    const a = sym(SYM.setCameraList);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            const n = bump("uMotionCamera::setCameraList");
            const cam = args[0], list = args[1];
            log(`===== setCameraList #${n}: camera ${cam} (${classOf(cam)}) <- rCameraList ${list} (${list.isNull() ? "null" : classOf(list)}) =====`);
            if (!list.isNull()) dumpObject(list, 0x100, "rCameraList (.lcm contents)");
            if (n <= 4) dumpObject(cam, CFG.DUMP_LEN, "motion camera at setCameraList");
        },
    });
    log("hooked uMotionCamera::setCameraList");
}

function hookMcApply() {
    const a = sym(SYM.mcApply);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            const n = bump("uMotionCamera::applyWorldOffset");
            if (!shouldLog(n)) return;
            const c = args[0];
            let flag = "?";
            try { flag = c.add(0x148).readPointer().toString(); } catch (e) { /* ignore */ }
            log(`game called uMotionCamera::applyWorldOffset #${n} cam=${c} [this+0x148]=${flag}` +
                (flag === "0x0" ? " (will apply)" : " (non-null: it returns early)"));
        },
    });
}

function hookFrameFn(label, name) {
    const a = Module.findExportByName(LIB, name);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            const n = bump(label);
            this.n = n;
            this.on = shouldLog(n);
            if (this.on) {
                this.cam = args[0];
                this.before = snap(this.cam, CFG.DUMP_LEN);
                if (n === 1) dumpObject(this.cam, CFG.DUMP_LEN, `${label} object (first call)`);
            }
        },
        onLeave() {
            if (!this.on) return;
            log(`${label} #${this.n} this=${this.cam} class=${classOf(this.cam)}`);
            diffMem(this.before, snap(this.cam, CFG.DUMP_LEN), `  ${label} fields rewritten`);
        },
    });
    log(`hooked ${label}`);
}

function hookViewMat(label, name) {
    const a = Module.findExportByName(LIB, name);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            const n = bump(label);
            this.n = n;
            this.on = shouldLog(n);
            this.sret = ARM64 ? this.context.x8 : args[0];
            this.self = ARM64 ? args[0] : args[1];
        },
        onLeave() {
            if (!this.on) return;
            dumpMem(this.sret, 64, `${label} #${this.n} returned view matrix (cam ${this.self}, ${classOf(this.self)})`);
        },
    });
    log(`hooked ${label}`);
}

/** .lcm sampling functions. getPos/getTarget write a 16-byte vector through the hidden return pointer. */
function hookInfoGetter(label, name, vec16, shift) {
    const a = sym(name);
    if (!a) return;
    Interceptor.attach(a, {
        onEnter(args) {
            this.n = bump(label);
            this.sret = vec16 ? (ARM64 ? this.context.x8 : args[0]) : null;
            this.info = ARM64 ? args[0] : args[1];
        },
        onLeave() {
            if (!this.sret) return;
            if (CFG.APPLY_OFFSET && shift) {
                try { addOffsetAt(this.sret); } catch (e) { log(`${label}: could not patch result: ${e}`); }
            }
            if (shouldLog(this.n)) {
                let v = "?";
                try { v = [0, 4, 8, 12].map(o => f32txt(this.sret.add(o).readFloat())).join(", "); } catch (e) { /* ignore */ }
                log(`${label} #${this.n} CAMERA_INFO=${this.info} returned (${v})` + (shift && CFG.APPLY_OFFSET ? " [offset applied]" : ""));
            }
        },
    });
    log(`hooked ${label}`);
}

function installHooks() {
    buildVtableIndex();
    hookChangeCamera();
    hookSetCameraList();
    hookMcApply();
    for (const [l, s] of FRAME_FNS) hookFrameFn(l, s);
    for (const [l, s] of VIEWMAT_FNS) hookViewMat(l, s);
    hookInfoGetter("CAMERA_INFO::getPos", SYM.infoPos, true, true);
    hookInfoGetter("CAMERA_INFO::getTarget", SYM.infoTarget, true, true);
    hookInfoGetter("CAMERA_INFO::getQuat", SYM.infoQuat, true, false);
    hookInfoGetter("CAMERA_INFO::getFov", SYM.infoFov, false, false);

    setInterval(() => {
        log(`[heartbeat] calls so far: ${JSON.stringify(stats)} offset=(${offset.x.toFixed(2)}, ${offset.y.toFixed(2)}, ${offset.z.toFixed(2)})`);
        if (activeCameraPtr) dumpMem(activeCameraPtr, 0x100, `active camera ${activeCameraPtr} (${classOf(activeCameraPtr)})`);
    }, CFG.HEARTBEAT_MS);
    log("ready. Walk around / change scenes; then pull freecam_dump.txt or read logcat.");
}

// ───────────────────────── socket bridge (overlay -> game) ─────────────────────────
function handleMessage(msg) {
    state.dx = msg.dx || 0;
    state.dy = msg.dy || 0;
    state.dz = msg.dz || 0;
    if (msg.reset) { offset.x = offset.y = offset.z = 0; }
    if (msg.cmd === "dump" && activeCameraPtr) dumpObject(activeCameraPtr, CFG.DUMP_LEN, "active camera (requested)");
}

function startSocketServer() {
    Socket.listen({ host: "127.0.0.1", port: LISTEN_PORT }).then((listener) => {
        log(`listening for overlay on 127.0.0.1:${LISTEN_PORT}`);
        function acceptLoop() {
            listener.accept().then((conn) => {
                log("overlay connected");
                let buffered = "";
                conn.setNoDelay(true);
                function readLoop() {
                    conn.input.read(4096).then((data) => {
                        if (data.byteLength === 0) { log("overlay disconnected"); return; }
                        buffered += Buffer.from(data).toString("utf8");
                        let idx;
                        while ((idx = buffered.indexOf("\n")) >= 0) {
                            const line = buffered.slice(0, idx);
                            buffered = buffered.slice(idx + 1);
                            try { handleMessage(JSON.parse(line)); } catch (e) { /* ignore malformed line */ }
                        }
                        readLoop();
                    }).catch((e) => log(`read error: ${e}`));
                }
                readLoop();
                acceptLoop();
            }).catch((e) => log(`accept error: ${e}`));
        }
        acceptLoop();
    }).catch((e) => log(`listen failed: ${e}`));
}

// ───────────────────────── startup ─────────────────────────
function waitForGameLib(onReady) {
    // With frida-gadget the script runs while the game's own library is still being
    // linked (the gadget is one of its dependencies), so poll until it is mapped.
    let tries = 0;
    (function poll() {
        if (Process.findModuleByName(LIB)) { setTimeout(onReady, 500); return; }
        if (++tries % 20 === 0) log(`still waiting for ${LIB}...`);
        setTimeout(poll, 100);
    })();
}

function main() {
    try { openLogFile(); } catch (e) { console.log(`[freecam] log file setup failed: ${e}`); }
    log(`hook.js starting (arch=${Process.arch}, pid=${Process.id})`);
    startSocketServer();
    waitForGameLib(() => {
        try {
            installHooks();
            setTimeout(tick, 16);
        } catch (e) {
            log(`init failed: ${e}\n${e.stack || ""}`);
        }
    });
}

main();

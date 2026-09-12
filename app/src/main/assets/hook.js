/*
 * hook.js — runs inside the game process via frida-inject.
 *
 * Design (v1, conservative):
 * Rather than constructing a brand-new uFreeCamera object (its exact
 * sizeof() isn't confirmed yet, and getting it wrong means heap
 * corruption), we hook sAppCamera::changeCamera(uCamera*), which the
 * game calls naturally on every camera swap (cutscene <-> investigation,
 * etc). That gives us a live, always-current pointer to whatever uCamera
 * subclass is presently active, with zero guessing about object layout.
 * We then call the base class's own uCamera::applyWorldOffset() on that
 * pointer every tick, driven by deltas streamed from the overlay app
 * over a local TCP socket.
 *
 * This won't feel like an unconstrained 6DOF free cam yet — it rides on
 * top of whatever camera behavior is already active and just offsets
 * it — but it's a safe, working first step. Swapping in a true
 * uFreeCamera instance is the natural next iteration once its size is
 * confirmed (see NOTES.md).
 */

const LIB = "libGS5_Android.so";
const LISTEN_PORT = 27042; // must match OverlayService.HOOK_PORT

const SYM_CHANGE_CAMERA = "_ZN10sAppCamera12changeCameraEP7uCamera";
const SYM_APPLY_OFFSET = "_ZN7uCamera16applyWorldOffsetERK9MtVector3S2_";

let activeCameraPtr = null;
let applyWorldOffsetFn = null;

// Mutable state written by the socket listener, read by the tick loop.
const state = { dx: 0.0, dy: 0.0, dz: 0.0 };

function log(msg) {
    console.log(`[freecam] ${msg}`);
}

function findLib() {
    const mod = Process.findModuleByName(LIB);
    if (!mod) {
        throw new Error(`${LIB} not loaded yet`);
    }
    return mod;
}

function hookChangeCamera() {
    const mod = findLib();
    const addr = mod.findExportByName
        ? mod.findExportByName(SYM_CHANGE_CAMERA)
        : Module.findExportByName(LIB, SYM_CHANGE_CAMERA);
    if (!addr) {
        throw new Error(`symbol not found: ${SYM_CHANGE_CAMERA}`);
    }

    Interceptor.attach(addr, {
        onEnter(args) {
            // args[0] = this (sAppCamera*), args[1] = uCamera* being activated
            const cam = args[1];
            if (!cam.isNull()) {
                activeCameraPtr = cam;
                log(`active camera changed -> ${cam}`);
            }
        }
    });
    log(`hooked sAppCamera::changeCamera at ${addr}`);
}

function resolveApplyOffset() {
    const addr = Module.findExportByName(LIB, SYM_APPLY_OFFSET);
    if (!addr) {
        throw new Error(`symbol not found: ${SYM_APPLY_OFFSET}`);
    }
    // void uCamera::applyWorldOffset(this, const MtVector3& pos, const MtVector3& rot)
    // References are passed as pointers under the AArch64 Itanium ABI.
    applyWorldOffsetFn = new NativeFunction(addr, "void", ["pointer", "pointer", "pointer"]);
    log(`resolved applyWorldOffset at ${addr}`);
}

function tick() {
    if (activeCameraPtr && applyWorldOffsetFn) {
        if (state.dx !== 0 || state.dy !== 0 || state.dz !== 0) {
            const posBuf = Memory.alloc(12); // MtVector3 = 3 floats
            const rotBuf = Memory.alloc(12); // unused for now, zeroed
            posBuf.writeFloat(state.dx * 0.05);
            posBuf.add(4).writeFloat(state.dy * 0.05);
            posBuf.add(8).writeFloat(state.dz * 0.05);
            rotBuf.writeFloat(0);
            rotBuf.add(4).writeFloat(0);
            rotBuf.add(8).writeFloat(0);
            applyWorldOffsetFn(activeCameraPtr, posBuf, rotBuf);
        }
    }
    setTimeout(tick, 16); // ~60Hz
}

// --- socket bridge: OverlayService connects here and streams JSON lines ---
function startSocketServer() {
    Socket.listen({ host: "127.0.0.1", port: LISTEN_PORT }).then((listener) => {
        log(`listening for overlay on 127.0.0.1:${LISTEN_PORT}`);
        function acceptLoop() {
            listener.accept().then((conn) => {
                log("overlay connected");
                let buffered = "";
                conn.setNoDelay(true);
                const reader = conn.input;
                function readLoop() {
                    reader.read(4096).then((data) => {
                        if (data.byteLength === 0) {
                            log("overlay disconnected");
                            return;
                        }
                        buffered += Buffer.from(data).toString("utf8");
                        let idx;
                        while ((idx = buffered.indexOf("\n")) >= 0) {
                            const line = buffered.slice(0, idx);
                            buffered = buffered.slice(idx + 1);
                            try {
                                const msg = JSON.parse(line);
                                state.dx = msg.dx || 0;
                                state.dy = msg.dy || 0;
                                state.dz = msg.dz || 0;
                            } catch (e) {
                                // ignore malformed line
                            }
                        }
                        readLoop();
                    }).catch((e) => log(`read error: ${e}`));
                }
                readLoop();
                acceptLoop(); // accept next connection too
            }).catch((e) => log(`accept error: ${e}`));
        }
        acceptLoop();
    }).catch((e) => log(`listen failed: ${e}`));
}

function main() {
    try {
        hookChangeCamera();
        resolveApplyOffset();
        startSocketServer();
        setTimeout(tick, 16);
        log("ready — waiting for a camera change event to capture a target");
    } catch (e) {
        log(`init failed: ${e}`);
    }
}

main();

package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;

/**
 * An immutable TRS transform (translation, uniform-or-not scale, rotation
 * quaternion) plus parent→child composition — the math behind the model
 * transform tree (DESIGN.md §10.7). No shear; that's all display entities can
 * represent and all a factory model needs.
 *
 * <p>Composition follows standard scene-graph rules:
 * {@code worldScale = parentScale ⊙ localScale},
 * {@code worldRot = parentRot * localRot},
 * {@code worldTranslation = parentTranslation + parentRot · (parentScale ⊙ localTranslation)}.
 * Each leaf's world transform is then handed to a {@link PacketDisplay} as the
 * display's translation / scale / left-rotation.
 */
public final class MTransform {

    public final float tx, ty, tz;        // translation
    public final float sx, sy, sz;        // scale
    public final float qx, qy, qz, qw;    // rotation quaternion

    public MTransform(float tx, float ty, float tz,
                      float sx, float sy, float sz,
                      float qx, float qy, float qz, float qw) {
        this.tx = tx; this.ty = ty; this.tz = tz;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.qx = qx; this.qy = qy; this.qz = qz; this.qw = qw;
    }

    public static MTransform identity() {
        return new MTransform(0, 0, 0, 1, 1, 1, 0, 0, 0, 1);
    }

    /** Build from author-friendly offset / scale / euler-degrees (yaw,pitch,roll = Y,X,Z). */
    public static MTransform of(double[] offset, double[] scale, double[] eulerDeg) {
        float[] q = eulerToQuat(
                (float) Math.toRadians(eulerDeg[0]),
                (float) Math.toRadians(eulerDeg[1]),
                (float) Math.toRadians(eulerDeg[2]));
        return new MTransform(
                (float) offset[0], (float) offset[1], (float) offset[2],
                (float) scale[0], (float) scale[1], (float) scale[2],
                q[0], q[1], q[2], q[3]);
    }

    /**
     * Full TRS compose (this = parent, arg = local) — parent scale multiplies the
     * child. Used <b>within a node</b> to fold its own animation onto its local
     * transform (so a {@code pulse} scales that part).
     */
    public MTransform compose(MTransform local) {
        float wsx = sx * local.sx, wsy = sy * local.sy, wsz = sz * local.sz;
        // child translation in parent space: scale, then rotate, then offset
        float lx = sx * local.tx, ly = sy * local.ty, lz = sz * local.tz;
        float[] r = rotate(qx, qy, qz, qw, lx, ly, lz);
        float wtx = tx + r[0], wty = ty + r[1], wtz = tz + r[2];
        float[] wq = mul(qx, qy, qz, qw, local.qx, local.qy, local.qz, local.qw);
        return new MTransform(wtx, wty, wtz, wsx, wsy, wsz, wq[0], wq[1], wq[2], wq[3]);
    }

    /**
     * Parent→child compose that does <b>not</b> propagate parent scale: the child
     * keeps its own scale, and inherits only the parent's position + rotation.
     * This is the tree-walk rule (DESIGN.md §10.7) — scaling a group repositions
     * nothing and resizes no child; each part's scale is purely local.
     */
    public MTransform composeNoScale(MTransform local) {
        float[] r = rotate(qx, qy, qz, qw, local.tx, local.ty, local.tz);
        float wtx = tx + r[0], wty = ty + r[1], wtz = tz + r[2];
        float[] wq = mul(qx, qy, qz, qw, local.qx, local.qy, local.qz, local.qw);
        return new MTransform(wtx, wty, wtz, local.sx, local.sy, local.sz, wq[0], wq[1], wq[2], wq[3]);
    }

    /** A copy with the translation replaced. */
    public MTransform at(float x, float y, float z) { return new MTransform(x, y, z, sx, sy, sz, qx, qy, qz, qw); }

    /** A copy turned by {@code deg} about its OWN axis ('x' | 'y' | 'z', local frame) — spins a centred part in place. */
    public MTransform rotatedLocal(char axis, double deg) {
        float h = (float) Math.toRadians(deg) / 2f, sn = (float) Math.sin(h), cs = (float) Math.cos(h);
        float ax = axis == 'x' ? sn : 0, ay = axis == 'y' ? sn : 0, az = axis == 'z' ? sn : 0;
        float[] q = mul(qx, qy, qz, qw, ax, ay, az, cs);
        return new MTransform(tx, ty, tz, sx, sy, sz, q[0], q[1], q[2], q[3]);
    }

    /** A copy translated by (dx,dy,dz) — used to auto-centre item/text displays. */
    public MTransform translated(float dx, float dy, float dz) {
        return new MTransform(tx + dx, ty + dy, tz + dz, sx, sy, sz, qx, qy, qz, qw);
    }

    public Vector3f translation() { return new Vector3f(tx, ty, tz); }
    public Vector3f scaleVec()    { return new Vector3f(sx, sy, sz); }
    public Quaternion4f leftRotation() { return new Quaternion4f(qx, qy, qz, qw); }

    /** Exact field equality — a static part recomputes to the identical transform each frame. */
    public boolean same(MTransform o) {
        return o != null && tx == o.tx && ty == o.ty && tz == o.tz
                && sx == o.sx && sy == o.sy && sz == o.sz
                && qx == o.qx && qy == o.qy && qz == o.qz && qw == o.qw;
    }

    /**
     * Blend per-channel animation deltas: translations add, scales multiply,
     * rotations compose. Keeps channels independent (a bob + pulse + spin don't
     * cross-contaminate), which is what {@link #applyAnimAndPivot} expects.
     */
    public static MTransform blend(java.util.List<MTransform> deltas) {
        float tx = 0, ty = 0, tz = 0, sx = 1, sy = 1, sz = 1, qx = 0, qy = 0, qz = 0, qw = 1;
        for (MTransform d : deltas) {
            tx += d.tx; ty += d.ty; tz += d.tz;
            sx *= d.sx; sy *= d.sy; sz *= d.sz;
            float[] q = mul(qx, qy, qz, qw, d.qx, d.qy, d.qz, d.qw);
            qx = q[0]; qy = q[1]; qz = q[2]; qw = q[3];
        }
        return new MTransform(tx, ty, tz, sx, sy, sz, qx, qy, qz, qw);
    }

    /**
     * Fold an animation delta onto this static local transform and bake a pivot:
     * rotation + scale happen around the point {@code (px,py,pz)} in the node's
     * unit-block space (0..1), instead of the corner. Returns the node's final
     * local transform for the frame.
     *
     * <p>Derivation: the display applies {@code T + L·(S·v)} to block vertices
     * {@code v}, rotating about the corner. To rotate/scale about a scaled pivot
     * {@code sp = S⊙p}, set {@code T = offset + sp − L·sp}.
     */
    public MTransform applyAnimAndPivot(MTransform anim, double px, double py, double pz) {
        float[] R = mul(qx, qy, qz, qw, anim.qx, anim.qy, anim.qz, anim.qw);  // static ∘ anim rotation
        float Sx = sx * anim.sx, Sy = sy * anim.sy, Sz = sz * anim.sz;        // static × anim scale
        float ox = tx + anim.tx, oy = ty + anim.ty, oz = tz + anim.tz;        // offset + anim translation
        float spx = (float) (Sx * px), spy = (float) (Sy * py), spz = (float) (Sz * pz);
        float[] rsp = rotate(R[0], R[1], R[2], R[3], spx, spy, spz);
        return new MTransform(
                ox + spx - rsp[0], oy + spy - rsp[1], oz + spz - rsp[2],
                Sx, Sy, Sz, R[0], R[1], R[2], R[3]);
    }

    // --- quaternion helpers -------------------------------------------------

    /** Euler (radians, applied Z then Y then X) → quaternion {x,y,z,w}. */
    private static float[] eulerToQuat(float y, float x, float z) {
        float cy = (float) Math.cos(y * 0.5f), sy = (float) Math.sin(y * 0.5f);
        float cx = (float) Math.cos(x * 0.5f), sx = (float) Math.sin(x * 0.5f);
        float cz = (float) Math.cos(z * 0.5f), sz = (float) Math.sin(z * 0.5f);
        // qY * qX * qZ
        float[] qy = {0, sy, 0, cy};
        float[] qx = {sx, 0, 0, cx};
        float[] qz = {0, 0, sz, cz};
        float[] t = mul(qy[0], qy[1], qy[2], qy[3], qx[0], qx[1], qx[2], qx[3]);
        return mul(t[0], t[1], t[2], t[3], qz[0], qz[1], qz[2], qz[3]);
    }

    /** Hamilton product a*b → {x,y,z,w}. */
    private static float[] mul(float ax, float ay, float az, float aw,
                               float bx, float by, float bz, float bw) {
        return new float[]{
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz
        };
    }

    /** Rotate a world-space vector by the INVERSE of this transform's rotation (into the display's local frame). */
    public float[] unrotateVec(float vx, float vy, float vz) { return rotate(-qx, -qy, -qz, qw, vx, vy, vz); }

    /** Rotate a model-space vector by this transform's rotation (no translation). */
    public float[] rotateVec(float vx, float vy, float vz) { return rotate(qx, qy, qz, qw, vx, vy, vz); }

    /** A local point (in this display's 0..scale box) → its offset from the origin, in world axes. */
    public float[] apply(float x, float y, float z) {
        float[] r = rotate(qx, qy, qz, qw, x, y, z);
        return new float[]{ tx + r[0], ty + r[1], tz + r[2] };
    }

    /** Rotate vector (vx,vy,vz) by quaternion (qx,qy,qz,qw) → {x,y,z}. */
    private static float[] rotate(float qx, float qy, float qz, float qw,
                                  float vx, float vy, float vz) {
        // t = 2 * cross(q.xyz, v)
        float tx = 2 * (qy * vz - qz * vy);
        float ty = 2 * (qz * vx - qx * vz);
        float tz = 2 * (qx * vy - qy * vx);
        // v + qw*t + cross(q.xyz, t)
        return new float[]{
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx)
        };
    }
}

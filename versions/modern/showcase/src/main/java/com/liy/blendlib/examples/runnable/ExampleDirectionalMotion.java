package com.liy.blendlib.examples.runnable;

/** Consumer-owned motion and yaw transform; animation receives measured collision-resolved velocity. */
public final class ExampleDirectionalMotion {
    public static final String TAG="blendlib_directional";
    public record Local(double forward,double left) { }
    public record World(double x,double z) { }
    private ExampleDirectionalMotion() { }
    /** Smooth circular traversal with a stationary interval every 20 seconds. */
    public static World requestedVelocity(int tick,double yawDegrees) {
        int t=Math.floorMod(tick,400);
        double envelope=t<40||t>=360?0:t<80?(1-Math.cos((t-40)*Math.PI/40))/2:t>320?(1+Math.cos((t-320)*Math.PI/40))/2:1;
        double angle=(t-80)*2*Math.PI/240;
        double speed=.12*envelope;
        return toWorld(speed*Math.cos(angle),speed*Math.sin(angle),yawDegrees);
    }
    /** Minecraft yaw zero faces +Z; positive yaw turns toward -X. Left is +X at yaw zero. */
    public static Local toLocal(double dx,double dz,double yawDegrees) {
        double yaw=Math.toRadians(yawDegrees),s=Math.sin(yaw),c=Math.cos(yaw);
        return new Local(-s*dx+c*dz,c*dx+s*dz);
    }
    public static World toWorld(double forward,double left,double yawDegrees) {
        double yaw=Math.toRadians(yawDegrees),s=Math.sin(yaw),c=Math.cos(yaw);
        return new World(-s*forward+c*left,c*forward+s*left);
    }
}

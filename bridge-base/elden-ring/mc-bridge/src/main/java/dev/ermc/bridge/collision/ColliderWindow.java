/*
 * Adapted from EldenCraft @ ba7591c: snapped window and revision-owned snapshot merge.
 * MIT License
 *
 * Copyright (c) 2026 Adam Grzegorzewski
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package dev.ermc.bridge.collision;

/** Snapped local voxel window; bounds stay within 48 x 32 x 48 blocks, with bounded motion lead. */
public record ColliderWindow(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
    private static final int SNAP=4,RADIUS=12,VERTICAL=8;
    public ColliderWindow {
        if(minX>maxX||minY>maxY||minZ>maxZ||(long)maxX-minX+1>48
                ||(long)maxY-minY+1>32||(long)maxZ-minZ+1>48)throw new IllegalArgumentException("Collider window bounds");
    }
    public static ColliderWindow around(double x,double y,double z,double vx,double vy,double vz){
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>29_999_900||Math.abs(y)>29_999_900||Math.abs(z)>29_999_900)
            throw new IllegalArgumentException("Collider centre");
        double dx=lead(vx),dy=lead(vy),dz=lead(vz);
        return new ColliderWindow(down(Math.min(x,x+dx)-RADIUS),down(Math.min(y,y+dy)-VERTICAL),down(Math.min(z,z+dz)-RADIUS),
            up(Math.max(x,x+dx)+RADIUS),up(Math.max(y,y+dy)+VERTICAL),up(Math.max(z,z+dz)+RADIUS));
    }
    private static double lead(double speed){return Double.isFinite(speed)?Math.max(-8,Math.min(8,speed*.5)):0;}
    private static int down(double value){return Math.floorDiv((int)Math.floor(value),SNAP)*SNAP;}
    private static int up(double value){return Math.floorDiv((int)Math.floor(value),SNAP)*SNAP+SNAP-1;}
    public boolean contains(int x,int y,int z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
}

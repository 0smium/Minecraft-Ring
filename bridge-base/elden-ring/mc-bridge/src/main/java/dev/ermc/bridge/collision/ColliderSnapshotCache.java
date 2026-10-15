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

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** A window/revision owns its exact merge; nearby motion only reselects a capped result. */
final class ColliderSnapshotCache {
    private Object context;
    private ColliderWindow window;
    private long revision=Long.MIN_VALUE;
    private List<ColliderMerge.Box> merged=List.of(),selected=List.of();
    private double x,y,z;
    private int limit;
    List<ColliderMerge.Box> get(Object context,ColliderWindow window,long revision,double x,double y,double z,int limit,
            Supplier<List<ColliderMerge.Box>> scan){
        boolean changed=this.context!=context||!Objects.equals(this.window,window)||this.revision!=revision;
        if(changed){merged=List.copyOf(ColliderMerge.merge(scan.get()));this.context=context;this.window=window;this.revision=revision;}
        if(changed||this.limit!=limit||(merged.size()>limit&&(this.x!=x||this.y!=y||this.z!=z))){
            selected=merged.size()<=limit?merged:List.copyOf(ColliderMerge.nearestMerged(merged,x,y,z,limit));
            this.x=x;this.y=y;this.z=z;this.limit=limit;
        }
        return selected;
    }
    int mergedCount(){return merged.size();}
}

/*
Copyright 2011 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.filter;

import java.util.Arrays;
import kanzi.VideoEffect;


public class LightingEffect implements VideoEffect
{
    private final int width;
    private final int height;
    private final int radius;
    private final int minIntensity;
    private final int power;
    private final int[] normalXY;
    private final int[] distanceMap;
    private int lightX;
    private int lightY;
    private int[] heightMap;
    private final boolean bumpMapping;
    
    
    
    public LightingEffect(int width, int height, int lightX, int lightY, int radius,
            int minIntensity, int power, boolean bumpMapping)
    {
        if (height < 8)
            throw new IllegalArgumentException("The height must be at least 8");
        
        if (width < 8)
            throw new IllegalArgumentException("The width must be at least 8");
                
        this.lightX = lightX;
        this.lightY = lightY;
        this.radius = radius;
        this.width  = width;
        this.height = height;
        this.minIntensity = (minIntensity > 0) ? minIntensity : 0;
        this.power = 255 * power / 100;
        this.bumpMapping = bumpMapping;
        this.distanceMap = new int[radius*radius];
        
        if (this.bumpMapping == true)
            this.normalXY = new int[width*height];
        else
            this.normalXY = null;
        
        // Initialize the distance table
        int top = 1 << 16;
        int invR = top / this.radius;
        
        for (int y=0; y<this.radius; y++)
        {
            int y2 = y * y;
            int startLine = this.radius * y;
            
            for (int x=0; x<this.radius; x++)
            {
                int d = top - (int) (Math.sqrt(x*x+y2) * invR);
                d = (this.power * d) >> 16;
                d = (d > this.minIntensity) ? d : this.minIntensity;
                d = (d < 255) ? d : 255;
                this.distanceMap[startLine + x] = d;
            }
        }
        
    }
        
    
    private void calculateNormalMap(int[] rgb)
    {
        // Initialize the normal table
        int length = this.width * this.height;
        int idx = 0;
        int startLine = 0;
        
        if (this.heightMap == null)
            this.heightMap = new int[length];
        
        for (int j=0; j<this.height; j++)
        {
            for (int i=0; i<this.width; i++)
            {
                // Height of the pixel based on the grey scale
                int pixel = rgb[startLine+i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >>  8) & 0xFF;
                int b =  pixel & 0xFF;
                this.heightMap[idx++] = (r + g + b) / 3;
            }
            
            startLine += this.width;
        }
        
        // First and last lines
        Arrays.fill(this.normalXY, 0, this.width, 0);
        Arrays.fill(this.normalXY, length-this.width-1, length-1, 0);
        int hh = this.height - 1;
        int ww = this.width - 1;
        int offs = this.width;
        
        for (int y=1; y<hh; y++)
        {
            // First in line (normalX = 0)
            int delta = this.heightMap[offs+this.width] - this.heightMap[offs-this.width];
            this.normalXY[offs] = delta & 0xFFFF;
            offs++;
            
            for (int x=1; x<ww; x++, offs++)
            {
                // Pack normalX and normalY into one integer (16 bits + 16 bits)
                delta = this.heightMap[offs+1] - this.heightMap[offs-1];
                int tmp = (delta & 0xFFFF) << 16;
                delta = this.heightMap[offs+this.width] - this.heightMap[offs-this.width];
                this.normalXY[offs] = tmp | (delta & 0xFFFF);
            }
            
            // Last in line (normalX = 0)
            delta = this.heightMap[offs+this.width] - this.heightMap[offs-this.width];
            this.normalXY[offs] = delta & 0xFFFF;
            offs++;
        }
    }
    
    
    @Override
    public int[] apply(int[] src, int[] dst)
    {
        int x0 = - this.lightX;
        int x1 = this.width + x0;
        int y0 = - this.lightY;
        int y1 = this.height + y0 - 2;
        int rd = this.radius - 1;
        
        // Is there a bump mapping effect ?
        if (this.bumpMapping == true)
        {
            this.calculateNormalMap(src);
            int dstStart = 0;
            int srcStart = 0;
            
            for (int y=y0; y<y1; y++)
            {
                dstStart += this.width;
                int dstIdx = dstStart;
                srcStart += this.width;
                int srcIdx = srcStart;
                
                for (int x=x0; x<x1; x++)
                {
                    int normal = this.normalXY[dstIdx];
                    
                    // First, extract the normal X coord. (16 upper bits) out of normalXY
                    // Use a short first, then expand to an int (takes care of negative
                    // number expansion)
                    short tmp = (short) (normal >> 16);
                    int val = tmp - x;
                    int dx = (val > 0) ? val : -val;
                    dx = (dx <= rd) ? dx : rd;
                    
                    // Extract the normal Y coord. as a short then expand to an int
                    // (takes care of negative number expansion)
                    tmp = (short) (normal & 0xFFFF);
                    val = tmp - y;
                    int dy = (val > 0) ? val : -val;
                    dy = (dy <= rd) ? dy : rd;
                    
                    int intensity = this.distanceMap[dy*this.radius+dx];
                    int pixel = src[srcIdx++];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel & 0xFF;
                    r = (intensity * r) >> 8;
                    g = (intensity * g) >> 8;
                    b = (intensity * b) >> 8;
                    
                    dst[dstIdx++] = (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
        }
        else // No bump mapping: just lighting
        {
            int dstStart = 0;
            int srcStart = 0;
            
            for (int y=y0; y<y1; y++)
            {
                dstStart += this.width;
                srcStart += this.width;
                int dstIdx = dstStart;
                int srcIdx = srcStart;
                int dy = (y > 0) ? y : -y;
                dy = (dy <= rd) ? dy : rd;
                int yy = dy * this.radius;
                
                for (int x=x0; x<x1; x++)
                {
                    int dx = (x > 0) ? x : -x;
                    dx = (dx <= rd) ? dx : rd;
                    int intensity = this.distanceMap[yy+dx];
                    int pixel = src[srcIdx++];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel & 0xFF;
                    r = (intensity * r) >> 8;
                    g = (intensity * g) >> 8;
                    b = (intensity * b) >> 8;                    
                    dst[dstIdx++] = (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
        }
        
        return dst;
    }
    
    
    // Not thread safe
    public void moveLight(int x, int y)
    {
        this.lightX = x;
        this.lightY = y;
    }
    
}


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
import kanzi.VideoEffectWithOffset;


public class LightingEffect implements VideoEffectWithOffset
{
    private final int width;
    private final int height;
    private final int radius;
    private final int stride;
    private int offset;
    private int savedOffset;
    private final int minIntensity;
    private final int maxIntensity;
    private final int[] normalXY;
    private final int[] distanceMap;
    private int lightX;
    private int lightY;
    private int[] heightMap;
    private final boolean bumpMapping;
       
   
    public LightingEffect(int width, int height, int lightX, int lightY, int radius, boolean bumpMapping)
    {
       this(width, height, 0, width, lightX, lightY, radius, 0, 100, bumpMapping);
    }


    // power in % (max pixel intensity)
    public LightingEffect(int width, int height, int offset, int stride, int lightX, int lightY, int radius,
            int minIntensity, int power, boolean bumpMapping)
    {
        if (height < 8)
            throw new IllegalArgumentException("The height must be at least 8");
        
        if (width < 8)
            throw new IllegalArgumentException("The width must be at least 8");

        if ((minIntensity < 0) || (minIntensity > 255))
            throw new IllegalArgumentException("TThe minimum pixel intensity must be in [0.255]");

        this.lightX = lightX;
        this.lightY = lightY;
        this.radius = radius;
        this.width  = width;
        this.height = height;
        this.minIntensity = minIntensity;
        this.maxIntensity = (255*power/100) > minIntensity ? (255*power/100) : minIntensity;
        this.bumpMapping = bumpMapping;
        this.stride = stride;
        this.offset = offset;
        this.savedOffset = offset-1;
        this.distanceMap = new int[radius*radius];
        
        if (this.bumpMapping == true)
            this.normalXY = new int[width*height];
        else
            this.normalXY = null;
        
        // Initialize the distance table
        final int rd = this.radius;
        final int top = 1 << 16;
        final int invR = top / rd;
        
        for (int y=0; y<rd; y++)
        {
            final int y2 = y * y;
            final int startLine = rd * y;
            
            for (int x=0; x<rd; x++)
            {
                int d = top - (int) (Math.sqrt(x*x+y2) * invR);
                d = (this.maxIntensity * d) >> 16;
                d = (d > this.minIntensity) ? d : this.minIntensity;
                d = (d < 255) ? d : 255;
                this.distanceMap[startLine+x] = d;
            }
        }
        
    }
        
    
    private void calculateNormalMap(int[] rgb)
    {
        // Calculation previously completed ?
        if (this.savedOffset == this.offset)
           return;

        // Initialize the normal table
        final int length = this.width * this.height;
        int idx = 0;
        int startLine = this.offset;
        
        if (this.heightMap == null)
            this.heightMap = new int[length];

        final int[] map = this.heightMap;
        final int w = this.width;

        for (int j=0; j<this.height; j++)
        {
            for (int i=0; i<w; i++)
            {
                // Height of the pixel based on the grey scale
                final int pixel = rgb[startLine+i];
                final int r = (pixel >> 16) & 0xFF;
                final int g = (pixel >>  8) & 0xFF;
                final int b =  pixel & 0xFF;
                map[idx++] = (r + g + b) / 3;
            }
            
            startLine += this.stride;
        }
        
        // First and last lines
        Arrays.fill(this.normalXY, 0, w, 0);
        Arrays.fill(this.normalXY, length-w-1, length-1, 0);
        final int hh = this.height - 1;
        final int ww = this.width - 1;
        int offs = this.width;
        
        for (int y=1; y<hh; y++)
        {
            // First in line (normalX = 0)
            int delta = map[offs+w] - map[offs-w];
            this.normalXY[offs] = delta & 0xFFFF;
            offs++;
            
            for (int x=1; x<ww; x++, offs++)
            {
                // Pack normalX and normalY into one integer (16 bits + 16 bits)
                delta = map[offs+1] - map[offs-1];
                final int tmp = (delta & 0xFFFF) << 16;
                delta = map[offs+w] - map[offs-w];
                this.normalXY[offs] = tmp | (delta & 0xFFFF);
            }
            
            // Last in line (normalX = 0)
            delta = map[offs+w] - map[offs-w];
            this.normalXY[offs] = delta & 0xFFFF;
            offs++;
        }
    }
    
    
    @Override
    public int[] apply(int[] src, int[] dst)
    {
        int x0 = (this.lightX >= this.radius) ? this.lightX - this.radius : 0;
        int x1 = (this.lightX + this.radius) < this.width ? this.lightX + this.radius : this.width;
        int y0 = (this.lightY >= this.radius) ? this.lightY - this.radius : 0;
        int y1 = (this.lightY + this.radius) < this.height ? this.lightY + this.radius : this.height;
        final int[] normals = this.normalXY;
        final int[] intensities = this.distanceMap;
        final int rd = this.radius;
        
        // Is there a bump mapping effect ?
        if (this.bumpMapping == true)
        {
            this.calculateNormalMap(src);
            int dstStart = this.offset;
            int srcStart = this.offset;
            
            for (int y=y0; y<y1; y++)
            {
                int dstIdx = dstStart;
                int srcIdx = srcStart;
                
                for (int x=x0; x<x1; x++)
                {
                    final int normal = normals[y*this.width+x];
                    
                    // First, extract the normal X coord. (16 upper bits) out of normalXY
                    // Use a short first, then expand to an int (takes care of negative
                    // number expansion)
                    short tmp = (short) (normal >> 16);
                    int val = tmp - x + this.lightX;
                    int dx = (val > 0) ? val : -val;
                    dx = (dx < rd) ? dx : rd-1;
                    
                    // Extract the normal Y coord. as a short then expand to an int
                    // (takes care of negative number expansion)
                    tmp = (short) (normal & 0xFFFF);
                    val = tmp - y + this.lightY;
                    int dy = (val > 0) ? val : -val;
                    dy = (dy < rd) ? dy : rd-1;
                    
                    final int intensity = intensities[dy*rd+dx];
                    final int pixel = src[srcIdx++];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel & 0xFF;
                    r = (intensity * r) >> 8;
                    g = (intensity * g) >> 8;
                    b = (intensity * b) >> 8;
                    
                    dst[dstIdx++] = (r << 16) | (g << 8) | b;
                }

                dstStart += this.stride;
                srcStart += this.stride;
            }
        }
        else // No bump mapping: just lighting
        {
            int dstStart = this.offset;
            int srcStart = this.offset;
            
            for (int y=y0; y<y1; y++)
            {
                int dstIdx = dstStart;
                int srcIdx = srcStart;
                int dy = (y > this.lightY) ? y - this.lightY : this.lightY - y;
                dy = (dy < rd) ? dy : rd - 1;
                final int yy = dy * rd;
                
                for (int x=x0; x<x1; x++)
                {
                    int dx = (x > this.lightX) ? x - this.lightX : this.lightX - x;
                    dx = (dx < rd) ? dx : rd - 1;
                    final int intensity = intensities[yy+dx];
                    final int pixel = src[srcIdx++];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel & 0xFF;
                    r = (intensity * r) >> 8;
                    g = (intensity * g) >> 8;
                    b = (intensity * b) >> 8;                    
                    dst[dstIdx++] = (r << 16) | (g << 8) | b;
                }

                dstStart += this.stride;
                srcStart += this.stride;
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


    @Override
    public int getOffset()
    {
        return this.offset;
    }


    // Not thread safe
    @Override
    public boolean setOffset(int offset)
    {
        if (offset < 0)
            return false;

        this.savedOffset = this.offset;
        this.offset = offset;
        return true;
    }
}


/*
Copyright 2011, 2012 Frederic Langlet
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

import kanzi.Global;
import kanzi.VideoEffectWithOffset;


// An effect that simulates the ripple effect created by a rain drop falling
// into a liquid surface
public class RainDropEffect implements VideoEffectWithOffset
{
   private final int width;
   private final int height;
   private final int stride;
   private int offset;
   private int radius;
   private int amplitude;  // times 128
   private int phase;      // times 1024
   private int wavelength; // in pixels
   private int dropX;
   private int dropY;

   
   public RainDropEffect(int width, int height, int radius)
   {
      this(width, height, 0, width, radius, 100, 128, 0);
   }


   public RainDropEffect(int width, int height, int offset, int stride, int radius,
           int wavelength)
   {
      this(width, height, 0, width, radius, wavelength, 128, 0);
   }


   public RainDropEffect(int width, int height, int offset, int stride, int radius,
           int wavelength, int amplitude128, int phase1024)
   {
      this(width, height, 0, width, radius, wavelength, 128, 0, width/2, height/2);
   }
   
   // pahe1024 in radians
   public RainDropEffect(int width, int height, int offset, int stride, int radius,
           int wavelength, int amplitude128, int phase1024, int dropX, int dropY)
   {
      if (height < 8)
         throw new IllegalArgumentException("The height must be at least 8");

      if (width < 8)
         throw new IllegalArgumentException("The width must be at least 8");

      if (offset < 0)
         throw new IllegalArgumentException("The offset must be at least 0");

      if (stride < 8)
         throw new IllegalArgumentException("The stride must be at least 8");

      if (radius < 2)
         throw new IllegalArgumentException("The radius must be at least 2");

      if ((amplitude128 < 0) || (amplitude128 > 128))
         throw new IllegalArgumentException("The amplitude must be [0..128]");

      if ((phase1024 < 0) || (phase1024 > Global.PI_1024_MULT2))
         throw new IllegalArgumentException("The phase must be [0.." + Global.PI_1024_MULT2 + "]");

      if (wavelength < 2)
         throw new IllegalArgumentException("The wavelengtht must be at least 2");

      this.height = height;
      this.width = width;
      this.stride = stride;
      this.offset = offset;
      this.radius = radius;
      this.amplitude = amplitude128;
      this.phase = phase1024;
      this.wavelength = wavelength;
      this.dropX = dropX;
      this.dropY = dropY;
    }


    @Override
    public int[] apply(int[] src, int[] dst)
    {
       final int w = this.width;
       final int h = this.height;
       final int st = this.stride;
       final int r1024 = this.radius << 10;
       final int centerX = this.dropX;
       final int centerY = this.dropY;
       final int maxX = w - 1;
       final int maxY = h - 1;
       final int r2 = this.radius * this.radius;
       final int amplitude128 = this.amplitude;
       final int wl = wavelength;
       final int phase1024 = this.phase;
       int offs = this.offset;

       for (int y=0; y<w; y++)
       {
         final int dy = y - centerY;
         final int dy2 = dy*dy;
         final int y1024 = y << 10;

         for (int x=0; x<h; x++)
         {
	   final int dx = x - centerX;
	   final int d2 = dx*dx + dy2;

           if (d2 > r2)
           {
              // Outside of scope, just copy original pixel
              dst[offs+x] = src[offs+x];
              continue;
           }
           
           // Calculate displacement
           final int distance1024 = Global.sqrt(d2);
           final int angle1024 = ((distance1024 / wl * Global.PI_1024_MULT2) >> 10) - phase1024;
           int amount1024 = (amplitude128 * Global.sin(angle1024)) >> 7;
           amount1024 = (amount1024 * (r1024 - distance1024)) / r1024;
           
           if (distance1024 != 0)
              amount1024 = (amount1024 * (wl<<10)) / distance1024;

           final int srcX = ((x<<10) + dx*amount1024) >> 10;
           final int srcY = ( y1024  + dy*amount1024) >> 10;

           if ((srcX >= 0) && (srcX < maxX) && (srcY >= 0) && (srcY < maxY))
           {      
              final int xw256 = srcX % 256; // keep sign
              final int yw256 = srcY % 256; // keep sign
              final int idx = (int) (srcY * st + srcX);
              dst[offs+x] = bilinearInterpolateRGB(xw256, yw256, src[idx], src[idx+1], 
                      src[idx+st], src[idx+st+1]);
            }
           else
           {
              // Outside of scope, just copy original pixel
              dst[offs+x] = src[offs+x];
           }
	}

        offs += this.stride;
      }

      return dst;
    }

    
    private static int bilinearInterpolateRGB(int xRatio256, int yRatio256, int p0, int p1, int p2, int p3)
    {
       final int r0 = (p0 >> 16) & 0xFF;
       final int g0 = (p0 >>  8) & 0xFF;
       final int b0 =  p0 & 0xFF;
       final int r1 = (p1 >> 16) & 0xFF;
       final int g1 = (p1 >>  8) & 0xFF;
       final int b1 =  p1 & 0xFF;
       final int r2 = (p2 >> 16) & 0xFF;
       final int g2 = (p2 >>  8) & 0xFF;
       final int b2 =  p2 & 0xFF;
       final int r3 = (p3 >> 16) & 0xFF;
       final int g3 = (p3 >>  8) & 0xFF;
       final int b3 =  p3 & 0xFF;
       final int cx = 256 - xRatio256;
       final int cy = 256 - yRatio256;
       final int rval0 = cx * r0 + xRatio256 * r1;
       final int rval1 = cx * r2 + xRatio256 * r3;
       final int r = (cy * rval0 + yRatio256 * rval1) >> 16;
       final int gval0 = cx * g0 + xRatio256 * g1;
       final int gval1 = cx * g2 + xRatio256 * g3;
       final int g = (cy * gval0 + yRatio256 * gval1) >> 16;
       final int bval0 = cx * b0 + xRatio256 * b1;
       final int bval1 = cx * b2 + xRatio256 * b3;
       final int b = (cy * bval0 + yRatio256 * bval1) >> 16;
       return (r << 16) | (g << 8) | b;
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

        this.offset = offset;
        return true;
    }
    
    
    // Return 128 * amplitude
    public int getAmplitude()
    {
        return this.amplitude;
    }


    // Not thread safe
    public boolean setAmplitude(int amplitude128)
    {
        if ((amplitude128 < 0) || (amplitude128 > 128))
           return false;

        this.amplitude = amplitude128;
        return true;
    }
    
    
    // Return 1024 * phase in radians
    public int getPhase()
    {
        return this.phase;
    }


    // Not thread safe
    public boolean setPhase(int phase1024)
    {
        if ((phase1024 < 0) || (phase1024 > Global.PI_1024_MULT2))
           return false;

        this.phase = phase1024;
        return true;
    }
    
    
    public int getWaveLength()
    {
        return this.wavelength;
    }


    // Not thread safe
    public boolean setWaveLength(int wavelength)
    {
        if (wavelength < 2)
           return false;

        this.wavelength = wavelength;
        return true;
    }
        
 }
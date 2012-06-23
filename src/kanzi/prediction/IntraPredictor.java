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

package kanzi.prediction;

import java.util.TreeSet;


// Class used to predict a block based on its neighbors in the frame
public class IntraPredictor
{
   public enum Mode
   {
      AVERAGE_UL(0),   // Average upper-left
      AVERAGE_UR(1),   // Average upper-right
      DC_L(2),         // DC left
      DC_R(3),         // DC right
      HORIZONTAL_L(4), // Horizontal left
      HORIZONTAL_R(5), // Horizontal right
      VERTICAL(6),     // Vertical (top)
      REFERENCE(7);    // Other block used as reference

      // 3 bit value
      private final byte value;

      Mode(int value)
      {
         this.value = (byte) value;
      }

      public static Mode getMode(int val)
      {
         if (val == AVERAGE_UL.value)
            return AVERAGE_UL;

         if (val == AVERAGE_UR.value)
            return AVERAGE_UR;

         if (val == DC_L.value)
            return DC_L;

         if (val == DC_R.value)
            return DC_R;

         if (val == HORIZONTAL_L.value)
            return HORIZONTAL_L;

         if (val == HORIZONTAL_R.value)
            return HORIZONTAL_R;

         if (val == VERTICAL.value)
            return VERTICAL;

         if (val == REFERENCE.value)
            return REFERENCE;

         return null;
      }
   };

   private static final int MAX_VALUE = Integer.MAX_VALUE;

   private final int width;
   private final int height;
   private final int stride;
   private final TreeSet<BlockContext> set; // used during spatial search
   private final int sqrErrThreshold; // used to trigger spatial search
   private final int spatialShift;
   private final boolean isRGB;


   public IntraPredictor(int width, int height)
   {
      this(width, height, width, true);
   }


   public IntraPredictor(int width, int height, int stride, boolean isRGB)
   {
      this(width, height, stride, isRGB, 5);
   }


   public IntraPredictor(int width, int height, int stride, boolean isRGB, int errThreshold)
   {
     this(width, height, stride, isRGB, errThreshold, 4);
   }


   // errThreshold is the residue energy per pixel that would trigger a spatial
   // search for neighbor blocks. It is checked at the end of the 1st step of
   // prediction to possibly trigger a 2nd step (if the residue energy is too high).
   // a value of 0 means that the spatial search happens always (except if the
   // residue energy per pixel is 0 at the end of step 1)
   // a value of 256 means that the spatial search never happens.
   // spatialStep is the step used to find a spatial match (1,2,4 or 8)
   public IntraPredictor(int width, int height, int stride, boolean isRGB, 
           int errThreshold, int spatialStep)
   {
     if (height < 8)
         throw new IllegalArgumentException("The height must be at least 8");

     if (width < 8)
         throw new IllegalArgumentException("The width must be at least 8");

     if (stride < 8)
         throw new IllegalArgumentException("The stride must be at least 8");

     if ((height & 7) != 0)
         throw new IllegalArgumentException("The height must be a multiple of 8");

     if ((width & 7) != 0)
         throw new IllegalArgumentException("The width must be a multiple of 8");

     if ((stride & 7) != 0)
         throw new IllegalArgumentException("The stride must be a multiple of 8");

     if ((errThreshold < 0) || (errThreshold > 256))
         throw new IllegalArgumentException("The residue energy threshold per pixel must in [0..256]");

     if ((spatialStep != 1) && (spatialStep != 2) && (spatialStep != 4) && (spatialStep != 8))
         throw new IllegalArgumentException("The step for spatial match must be in [1,2,4,8] ");

     this.height = height;
     this.width = width;
     this.stride = stride;
     this.set = new TreeSet<BlockContext>();
     this.sqrErrThreshold = errThreshold * errThreshold;

     int shift = 3;

     while (spatialStep > 1)
     {
        shift--;
        spatialStep >>= 1;
     }

     // Control the number of blocks to search during spatial match
     this.spatialShift = shift;
     this.isRGB = isRGB;
   }


   // Compute block prediction (from other blocks) using 8 different methods (modes)
   // Another block (spatial or temporal) can be provided optionally
   // The input arrays must be frame channels (R,G,B or Y,U,V)
   // input is a block in a frame at offset iIdx (y*stride+x)
   // output is the difference block (compacted to dim*dim)
   // return energy for each prediction mode
   public Prediction[] computeResidues(int[] input, int ix, int iy,
           int[] other, int ox, int oy, Prediction[] predictions, int blockDim)
   {
      if ((ix < 0) || (ix >= this.width) || (iy < 0) || (iy >= this.height))
         return null;

      // The block dimension must be a multiple of 4
      if ((blockDim & 3) != 0)
         return null;

      // Limit block dimension to 64 for now
      if (blockDim > 64)
         return null;

      int minIdx = 0;

      for (Prediction p : predictions)
      {
         p.energy = MAX_VALUE;
         p.x = ix;
         p.y = iy;
         p.frame = input;
      }

      // First step, use different modes to find best match
      // Start with temporal prediction
      if (other != null)
      {
         Prediction p = predictions[Mode.REFERENCE.value];
         p.frame = other;
         p.x = ox;
         p.y = oy;
         p.energy = this.computeDiff(input, iy*this.stride+ix,
                 other, oy*this.stride+ox, p.residue, blockDim);

         if (p.energy == 0)
           return predictions;

         minIdx = Mode.REFERENCE.value;
      }

      // Compute block residues based on prediction modes
      this.computeDiff(input, ox, oy, predictions, blockDim);

      for (int i=0; i<predictions.length; i++)
      {
         // >= favor 'easier' modes (H or V) with higher value in case of equality
         if (predictions[minIdx].energy >= predictions[i].energy)
            minIdx = i;
      }

      final int minNrj = predictions[minIdx].energy;

      if (minNrj == 0)
         return predictions;

      // Check the residue energy
      if ((iy > 0) && (minNrj >= blockDim * blockDim * this.sqrErrThreshold))
      {
         // Second step: spatial search of best matching nearby block
         Prediction newPrediction = new Prediction(input, 0, 0, blockDim);

         // Do the search and update prediction energy, coordinates and result block
         this.computePartialDiff(input, ix, iy, blockDim, minNrj, newPrediction);

         // Is the new prediction an improvement ?
         if (newPrediction.energy < predictions[Mode.REFERENCE.value].energy)
         {
            predictions[Mode.REFERENCE.value].x = newPrediction.x;
            predictions[Mode.REFERENCE.value].y = newPrediction.y;
            predictions[Mode.REFERENCE.value].energy = newPrediction.energy;
            predictions[Mode.REFERENCE.value].residue = newPrediction.residue;

            // Create residue block for reference mode
            this.computeDiff(input, iy*this.stride+ix, input,
                    newPrediction.y*this.stride+newPrediction.x,
                    newPrediction.residue, blockDim);
         }
      }

      return predictions;
   }


   // Compute residue against another (spatial/temporal) block
   // Return energy of difference block
   private int computeDiff(int[] input, int iIdx, int[] other, int oIdx,
           int[] output, int blockDim)
   {
      final int st = this.stride;
      final int endj = iIdx + (st * blockDim);
      int k = 0;
      int energy = 0;

      if (other == null)
      {
          // Simple copy
          for (int j=iIdx; j<endj; j+=st)
          {
             final int endi = j + blockDim;

             for (int i=j; i<endi; i+=4)
             {
                final int val0 = input[i];
                final int val1 = input[i+1];
                final int val2 = input[i+2];
                final int val3 = input[i+3];
                output[k]   = val0;
                output[k+1] = val1;
                output[k+2] = val2;
                output[k+3] = val3;
                k += 4;
                energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
             }
          }
      }
      else
      {
         final int mask = (this.isRGB == true) ? 0xFF : -1;
         
         for (int j=iIdx; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
                final int val0 = (input[i]   & mask) - (other[oIdx]   & mask);
                final int val1 = (input[i+1] & mask) - (other[oIdx+1] & mask);
                final int val2 = (input[i+2] & mask) - (other[oIdx+2] & mask);
                final int val3 = (input[i+3] & mask) - (other[oIdx+3] & mask);
                output[k]   = val0;
                output[k+1] = val1;
                output[k+2] = val2;
                output[k+3] = val3;
                k += 4;
                energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
                oIdx += 4;
            }

            oIdx += (st - blockDim);
         }
      }

      return energy;
   }


   // Compute residue based on several prediction modes
   // Return energy of residue blocks
   // Proceed line by line (for cache) and avoid branches (for speed)
   // Example 8x8
   //       a0 a1 a2 a3 a4 a5 a6 a7
   //  b0   x0 x1 x2 x3 x4 x5 x6 x7   c0
   //  b1   x0 x1 x2 x3 x4 x5 x6 x7   c1
   //  b2   x0 x1 x2 x3 x4 x5 x6 x7   c2
   //  b3   x0 x1 x2 x3 x4 x5 x6 x7   c3
   //  b4   x0 x1 x2 x3 x4 x5 x6 x7   c4
   //  b5   x0 x1 x2 x3 x4 x5 x6 x7   c5
   //  b6   x0 x1 x2 x3 x4 x5 x6 x7   c6
   //  b7   x0 x1 x2 x3 x4 x5 x6 x7   c7
   private Prediction[] computeDiff(int[] input, int x, int y, Prediction[] predictions, int blockDim)
   {
      final int st = this.stride;
      final int start = y*st + x;
      final int endj = start + (st * blockDim);
      final int xMax = this.width - blockDim;
      final int mask = (this.isRGB == true) ? 0xFF : -1;
      int k = 0;
      int dc_l = 0;
      int dc_r = 0;
      int sum_l = 0;
      int sum_r = 0;
       
      if (y > 0)
      {
         predictions[Mode.VERTICAL.value].energy = 0;
         predictions[Mode.DC_L.value].energy = 0;
         predictions[Mode.DC_R.value].energy = 0;
         sum_l += blockDim;
         sum_r += blockDim;
         final int above = start - st;
  
         for (int i=0; i<blockDim; i++)
            dc_l += (input[above+i] & mask);
         
         dc_r = dc_l;
      }
      
      if (x > 0)
      {
         predictions[Mode.HORIZONTAL_L.value].energy = 0;
         predictions[Mode.DC_L.value].energy = 0;

         if (y > 0)
            predictions[Mode.AVERAGE_UL.value].energy = 0;
            
         for (int j=start; j<endj; j+=st)
            dc_l += (input[j-1] & mask);

         sum_l += blockDim;
         dc_l = (dc_l + (sum_l >> 1)) / sum_l;   
      }

      if (x < xMax)
      {
         predictions[Mode.HORIZONTAL_R.value].energy = 0;
         predictions[Mode.DC_R.value].energy = 0;

         if (y > 0)
            predictions[Mode.AVERAGE_UR.value].energy = 0;
            
         for (int j=start; j<endj; j+=st)
            dc_r += (input[j+blockDim] & mask);

         sum_r += blockDim;
         dc_r = (dc_r + (sum_r >> 1)) / sum_r;    
      }


      for (int j=start; j<endj; j+=st)
      {
         final int endi = j + blockDim;
         
         for (int i=j; i<endi; i+=4)
         {
            final int x0 = input[i]   & mask;
            final int x1 = input[i+1] & mask;
            final int x2 = input[i+2] & mask;
            final int x3 = input[i+3] & mask;

            if ((y > 0) || (x > 0))
            {
               // DC_L: xi-dc_l
               final int val0 = x0 - dc_l;
               final int val1 = x1 - dc_l;
               final int val2 = x2 - dc_l;
               final int val3 = x3 - dc_l;
               final Prediction p = predictions[Mode.DC_L.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
            }

            if ((y > 0) || (x < xMax))
            {
               // DC_R: xi-dc_r
               final int val0 = x0 - dc_r;
               final int val1 = x1 - dc_r;
               final int val2 = x2 - dc_r;
               final int val3 = x3 - dc_r;
               final Prediction p = predictions[Mode.DC_R.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
            }
            
            if (y > 0)
            {
               final int px0 = input[i-st]   & mask;
               final int px1 = input[i-st+1] & mask;
               final int px2 = input[i-st+2] & mask;
               final int px3 = input[i-st+3] & mask;

               {
                  // VERTICAL: xi-ai
                  final int above = start - st;
                  final int idx = above + i - j;
                  final int val0 = x0 - (input[idx]   & mask);
                  final int val1 = x1 - (input[idx+1] & mask);
                  final int val2 = x2 - (input[idx+2] & mask);
                  final int val3 = x3 - (input[idx+3] & mask);
                  final Prediction p = predictions[Mode.VERTICAL.value];
                  final int[] output = p.residue;
                  output[k]   = val0;
                  output[k+1] = val1;
                  output[k+2] = val2;
                  output[k+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               if (x > 0)
               {
                  // AVERAGE_UL: (xi,yi)-avg((xi,yi-1),(xi-1,yi))
                  final int xa = input[i-1] & mask;
                  int avg;
                  avg = (xa + px0) >> 1;
                  final int val0 = x0 - avg;
                  avg = (x0 + px1) >> 1;
                  final int val1 = x1 - avg;
                  avg = (x1 + px2) >> 1;
                  final int val2 = x2 - avg;
                  avg = (x2 + px3) >> 1;
                  final int val3 = x3 - avg;
                  final Prediction p = predictions[Mode.AVERAGE_UL.value];
                  final int[] output = p.residue;
                  output[k]   = val0;
                  output[k+1] = val1;
                  output[k+2] = val2;
                  output[k+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }
            }

            if (x > 0)
            {
               // HORIZONTAL_L: xi-bi
               final int b = input[i-1] & mask;
               final int val0 = x0 - b;
               final int val1 = x1 - b;
               final int val2 = x2 - b;
               final int val3 = x3 - b;
               final Prediction p = predictions[Mode.HORIZONTAL_L.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
            }

            if (x < xMax)
            {
               // HORIZONTAL_R: xi-ci
               final int c = input[i+blockDim] & mask;
               final int val0 = x0 - c;
               final int val1 = x1 - c;
               final int val2 = x2 - c;
               final int val3 = x3 - c;
               final Prediction p = predictions[Mode.HORIZONTAL_R.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
            }

            k += 4;
         }   
         
         if ((y > 0) && (x < xMax))
         {
            int xc = input[endi] & mask;    
            k -= blockDim;
            
            // Scan from right to left
            for (int i=endi-4; i>=j; i-=4)
            {
               // AVERAGE_UR: (xi,yi)-avg((xi,yi-1),(xi+1,yi))
               final int px0 = input[i-st]   & mask;
               final int px1 = input[i-st+1] & mask;
               final int px2 = input[i-st+2] & mask;
               final int px3 = input[i-st+3] & mask;
               final int x0 = input[i]   & mask;
               final int x1 = input[i+1] & mask;
               final int x2 = input[i+2] & mask;
               final int x3 = input[i+3] & mask;
               int avg;
               avg = (x1 + px0) >> 1;
               final int val0 = x0 - avg;
               avg = (x2 + px1) >> 1;
               final int val1 = x1 - avg;
               avg = (x3 + px2) >> 1;
               final int val2 = x2 - avg;
               avg = (xc + px3) >> 1;
               final int val3 = x3 - avg;
               final Prediction p = predictions[Mode.AVERAGE_UR.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               k += 4;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               xc = x0;
            }
         }
      }

      return predictions;
   }


   // Add residue to other block, return predicted block
   // Return output
   private int[] computeBlock(Prediction prediction, int[] output, int oIdx,
           int q, int blockDim)
   {
      final int st = this.stride;
      final int endj = oIdx + (st * blockDim);
      final int[] residue = prediction.residue;
      final int[] input = prediction.frame;
      int iIdx = prediction.y*this.stride + prediction.x;
      int k = 0;

      if (input == null)
      {
          // Simple scale/copy
          for (int j=oIdx; j<endj; j+=st)
          {
             final int endi = j + blockDim;

             for (int i=j; i<endi; i+=4)
             {
                output[j]   = q * residue[k];
                output[j+1] = q * residue[k+1];
                output[j+2] = q * residue[k+2];
                output[j+3] = q * residue[k+3];
                k += 4;
             }
          }
      }
      else
      {
         final int mask = (this.isRGB == true) ? 0xFF : -1;

         for (int j=oIdx; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
                output[i]   = q * residue[k]   + (input[iIdx]   & mask);
                output[i+1] = q * residue[k+1] + (input[iIdx+1] & mask);
                output[i+2] = q * residue[k+2] + (input[iIdx+2] & mask);
                output[i+3] = q * residue[k+3] + (input[iIdx+3] & mask);
                iIdx += 4;
                k += 4;
            }

            iIdx += (st - blockDim);
         }
      }

      return output;
   }


   // Create block in output at x,y from prediction mode, residue and input
   // residue is a blockDim*blockDim size block
   // input and output are width*height size frames
   // q is the quantizer (residue multiplier)
   public int[] computeBlock(Prediction prediction, int[] output, int x, int y,
           int q, int blockDim, Mode mode)
   {
      final int st = this.stride;
      final int start = (y * st) + x;
      final int endj = start + (st * blockDim);
      final int[] residue = prediction.residue;
      final int[] input = prediction.frame;
      final int mask = (this.isRGB == true) ? 0xFF : -1;
      int k = 0;

      if (mode == Mode.REFERENCE)
         return this.computeBlock(prediction, output, start, q, blockDim);

      if (mode == Mode.VERTICAL)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               // VERTICAL: xi+ai
               final int idx = start - st + i - j;
               output[i]   = r0 + (input[idx]   & mask);
               output[i+1] = r1 + (input[idx+1] & mask);
               output[i+2] = r2 + (input[idx+2] & mask);
               output[i+3] = r3 + (input[idx+3] & mask);
               k += 4;
            }
         }
      }
      else if (mode == Mode.AVERAGE_UL)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;
            int xa = input[j-1] & mask;

            for (int i=j; i<endi; i+=4)
            {
               final int above = i - st;
               final int px0 = input[above]   & mask;
               final int px1 = input[above+1] & mask;
               final int px2 = input[above+2] & mask;
               final int px3 = input[above+3] & mask;
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               // AVERAGE_UL: (xi,yi)+avg((xi,yi-1),(xi-1,yi))
               final int x0 = r0 + ((xa + px0) >> 1);
               final int x1 = r1 + ((x0 + px1) >> 1);
               final int x2 = r2 + ((x1 + px2) >> 1);
               final int x3 = r3 + ((x2 + px3) >> 1);
               output[i]   = x0;
               output[i+1] = x1;
               output[i+2] = x2;
               output[i+3] = x3;
               k += 4;
               xa = x3;
            }
         }
      }
      else if (mode == Mode.AVERAGE_UR)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;
            int xc = input[endi] & mask;

            for (int i=endi-4; i>=j ; i-=4)
            {
               final int above = i - st;
               final int px0 = input[above]   & mask;
               final int px1 = input[above+1] & mask;
               final int px2 = input[above+2] & mask;
               final int px3 = input[above+3] & mask;
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               // AVERAGE_UR: (xi,yi)+avg((xi,yi-1),(xi+1,yi))
               final int x3 = r3 + ((xc + px3) >> 1);
               final int x2 = r2 + ((x3 + px2) >> 1);
               final int x1 = r1 + ((x2 + px1) >> 1);
               final int x0 = r0 + ((x1 + px0) >> 1);
               output[i]   = x0;
               output[i+1] = x1;
               output[i+2] = x2;
               output[i+3] = x3;
               k += 4;
               xc = x0;
            }
         } 
      }
      else if ((mode == Mode.HORIZONTAL_L) || (mode == Mode.HORIZONTAL_R))
      {
         final int offs = (mode == Mode.HORIZONTAL_L) ? -1 : blockDim;

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
               // HORIZONTAL_L: xi+bi
               // HORIZONTAL_R: xi+ci
               final int val = input[i+offs] & mask;
               output[i]   = (q * residue[k])   + val;
               output[i+1] = (q * residue[k+1]) + val;
               output[i+2] = (q * residue[k+2]) + val;
               output[i+3] = (q * residue[k+3]) + val;
               k += 4;
            }
         }
      }
      else if ((mode == Mode.DC_L) || (mode == Mode.DC_R))
      {
         final int dc;
         final int above = start - st;

         if (mode == Mode.DC_L)
         {
            // dc=ai+bi
            int dc_l = 0;
            int sum = 0;

            if (y > 0)
            {
               for (int i=0; i<blockDim; i++)
                  dc_l += (input[above+i] & mask);
               
               sum += blockDim;
            }

            if (x > 0)
            {
              for (int j=start; j<endj; j+=st)
                 dc_l += (input[j-1] & mask);
               
               sum += blockDim;
            }
            
            dc = (dc_l + (sum >> 1)) / sum;
         }
         else
         {
            // dc=ai+ci
            int dc_r = 0;
            int sum = 0;

            if (y > 0)
            {
              for (int i=0; i<blockDim; i++)
                 dc_r += (input[above+i] & mask);
              
              sum += blockDim;
            }

            if (x < this.width - blockDim)
            {
              for (int j=start; j<endj; j+=st)
                 dc_r += (input[j+blockDim] & mask);
              
              sum += blockDim;
            }            

            dc = (dc_r + (sum >> 1)) / sum;
         }

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
               // DC_L: xi+dc_l
               // DC_R: xi+dc_r
               output[i]   = (q * residue[k])   + dc;
               output[i+1] = (q * residue[k+1]) + dc;
               output[i+2] = (q * residue[k+2]) + dc;
               output[i+3] = (q * residue[k+3]) + dc;
               k += 4;
            }
         }
      }

      return output;
   }


   // Spatial search
   // Base prediction on differeence with nearby blocks using 'winner update' strategy
   // Return energy and update prediction argument
   private int computePartialDiff(int[] input, int x, int y,
           int blockDim, int maxEnergy, Prediction prediction)
   {
      final int st = this.stride;
      final int adjust = (blockDim >= 8) ? blockDim >> 3 : 1;
      final int step = (blockDim >> this.spatialShift) * adjust;

      // Populate set of block candidates
      // Add blocks to compare against (blocks must already have been encoded/decoded
      // to avoid circular dependencies). Check against upper neighbors (current block
      // is XX):
      //    01 02 03 04 05
      //    06 07 08 09 10
      //       11 XX 12
      // step/candidates: 8=>12, 4=>35, 2=>117, 1=>425
      final int jstart = y - (blockDim << 1);

      for (int j=jstart; j<=y; j+=step)
      {
         if (j < 0)
            continue;

         final int istart = (j < y) ? x - (blockDim << 1) : x - blockDim;
         final int iend = (j < y) ? x + (blockDim << 1) : x + blockDim;

         for (int i=istart; i<=iend; i+=step)
         {
            if ((i < 0) || (i + blockDim >= this.width))
               continue;

            // Block candidates are not allowed to intersect with current block
            if ((j + blockDim > y) && (i + blockDim > x) && (i < x + blockDim))
               continue;

            this.set.add(BlockContext.getContext(prediction.frame, i, j));
         }
      }

      BlockContext ctx = null;

      // Critical speed path
      while (this.set.size() > 0)
      {
         // Select partial winner (lowest energy) to update
         ctx = this.set.pollFirst();

         // Full winner found ?
         if (ctx.line >= blockDim)
         {
            this.set.clear();
            break;
         }

         // Aliasing
         final int[] data = ctx.data;
         final int start = (y+ctx.line)*st + x;
         final int end = start + blockDim;
         final int mask = (this.isRGB == true) ? 0xFF : -1;
         int offs2 = (ctx.y+ctx.line)*st + ctx.x;
         int nrj = ctx.energy;

         // Compute line difference
         for (int i=start; i<end; i+=4)
         {
             final int val0 = (input[i]   & mask) - (data[offs2]   & mask);
             final int val1 = (input[i+1] & mask) - (data[offs2+1] & mask);
             final int val2 = (input[i+2] & mask) - (data[offs2+2] & mask);
             final int val3 = (input[i+3] & mask) - (data[offs2+3] & mask);
             nrj += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
             offs2 += 4;
         }
         
         ctx.energy = nrj;
         ctx.line++;

         // Put back current block context into sorted set (likely new position)
         if (nrj < maxEnergy)
            this.set.add(ctx);
      }

      if (ctx == null)
         return MAX_VALUE;
      
      // Return best result
      prediction.x = ctx.x;
      prediction.y = ctx.y;
      prediction.energy = ctx.energy;
      return prediction.energy;
   }


   private static class BlockContext implements Comparable<BlockContext>
   {
      int line;      // line to be processed
      int energy;    // energy so far
      int[] data;    // frame data
      int x;
      int y;

      private static BlockContext[] CACHE = init();
      private static int INDEX = 0;

      private static BlockContext[] init()
      {
         BlockContext[] res = new BlockContext[441]; // max block candidates per call

         for (int i=0; i<res.length; i++)
            res[i] = new BlockContext();

         return res;
      }


      public static BlockContext getContext(int[] data, int x, int y)
      {
         BlockContext res = CACHE[INDEX];

         if (++INDEX == CACHE.length)
            INDEX = 0;

         res.energy = 0;
         res.data = data;
         res.line = 0;
         res.x = x;
         res.y = y;
         return res;
      }


      @Override
      public int compareTo(BlockContext c)
      {
         final int res = this.energy - c.energy;

         if (res != 0)
            return res;

         return this.hashCode() - c.hashCode(); // random but not 0 unless same objects
      }


      @Override
      public boolean equals(Object o)
      {
         try
         {
            if (o == this)
               return true;

            if (o == null)
               return false;

            BlockContext c = (BlockContext) o;

            if (this.energy != c.energy)
               return false;

            return (this.hashCode() == c.hashCode()) ? true : false;
         }
         catch (ClassCastException e)
         {
            return false;
         }
      }
   }


   public static class Prediction
   {
      public int energy;
      public int[] frame;
      public int[] residue;
      public int x;
      public int y;


      public Prediction()
      {
         this.residue = new int[64*64];
      }


      public Prediction(int[] frame, int x, int y, int blockDim)
      {
         this.frame = frame;
         this.x = x;
         this.y = y;
         this.residue = new int[blockDim*blockDim];
      }
   }
}

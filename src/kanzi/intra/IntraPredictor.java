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

package kanzi.intra;

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


   public IntraPredictor(int width, int height)
   {
      this(width, height, width);
   }


   public IntraPredictor(int width, int height, int stride)
   {
      this(width, height, stride, 5);
   }


   public IntraPredictor(int width, int height, int stride, int errThreshold)
   {
     this(width, height, stride, errThreshold, 4);
   }


   // errThreshold is the residue energy per pixel that would trigger a spatial
   // search for neighbor blocks. It is checked at the end of the 1st step of
   // prediction to possibly trigger a 2nd step (if the residue energy is too high).
   // a value of 0 means that the spatial search happens always (except if the
   // residue energy per pixel is 0 at the end of step 1)
   // a value of 256 means that the spatial search never happens.
   // spatialStep is the step used to find a spatial match (1,2,4 or 8)
   public IntraPredictor(int width, int height, int stride, int errThreshold, int spatialStep)
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

      // The block dimension must be a multiple of 8
      if ((blockDim & 7) != 0)
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
         Prediction newPrediction;

         if (minIdx != Mode.REFERENCE.value)
         {
            // Reuse prediction object
            newPrediction = predictions[Mode.REFERENCE.value];
            newPrediction.frame = input;
         }
         else // Create prediction object
            newPrediction = new Prediction(input, ix, iy, blockDim);

         // Do the search and update prediction energy, coordinates and result block
         this.computePartialDiff(input, ix, iy, blockDim, minNrj, newPrediction);

         // Is the new prediction an improvement ?
         if (newPrediction.energy < minNrj)
         {
            if (newPrediction != predictions[Mode.REFERENCE.value])
            {
               predictions[Mode.REFERENCE.value].x = newPrediction.x;
               predictions[Mode.REFERENCE.value].y = newPrediction.y;
               predictions[Mode.REFERENCE.value].energy = newPrediction.energy;
               predictions[Mode.REFERENCE.value].residue = newPrediction.residue;
            }

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

             for (int i=j; i<endi; i+=8)
             {
                final int val0 = input[i];
                final int val1 = input[i+1];
                final int val2 = input[i+2];
                final int val3 = input[i+3];
                final int val4 = input[i+4];
                final int val5 = input[i+5];
                final int val6 = input[i+6];
                final int val7 = input[i+7];
                output[k]   = val0;
                output[k+1] = val1;
                output[k+2] = val2;
                output[k+3] = val3;
                output[k+4] = val4;
                output[k+5] = val5;
                output[k+6] = val6;
                output[k+7] = val7;
                k += 8;
                energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                        (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
             }
          }
      }
      else
      {
         for (int j=iIdx; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
                final int val0 = (input[i]   & 0xFF) - (other[oIdx]   & 0xFF);
                final int val1 = (input[i+1] & 0xFF) - (other[oIdx+1] & 0xFF);
                final int val2 = (input[i+2] & 0xFF) - (other[oIdx+2] & 0xFF);
                final int val3 = (input[i+3] & 0xFF) - (other[oIdx+3] & 0xFF);
                final int val4 = (input[i+4] & 0xFF) - (other[oIdx+4] & 0xFF);
                final int val5 = (input[i+5] & 0xFF) - (other[oIdx+5] & 0xFF);
                final int val6 = (input[i+6] & 0xFF) - (other[oIdx+6] & 0xFF);
                final int val7 = (input[i+7] & 0xFF) - (other[oIdx+7] & 0xFF);
                oIdx += 8;
                output[k]   = val0;
                output[k+1] = val1;
                output[k+2] = val2;
                output[k+3] = val3;
                output[k+4] = val4;
                output[k+5] = val5;
                output[k+6] = val6;
                output[k+7] = val7;
                k += 8;
                energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                        (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
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
      int k = 0;
      int dc_l = 0;
      int dc_r = 0;

      if (y > 0)
      {
         final int above = start - st;
         predictions[Mode.VERTICAL.value].energy = 0;

         if (x > 0)
         {
            predictions[Mode.DC_L.value].energy = 0;
            predictions[Mode.AVERAGE_UL.value].energy = 0;

            // dc=ai+bi
            for (int i=0; i<blockDim; i++)
               dc_l += (input[above+i] & 0xFF);

            for (int j=start; j<endj; j+=st)
               dc_l += (input[j-1] & 0xFF);

            dc_l = (dc_l + blockDim) / (blockDim + blockDim);
         }

         if (x < xMax)
         {
            predictions[Mode.DC_R.value].energy = 0;
            predictions[Mode.AVERAGE_UR.value].energy = 0;

            // dc=ai+ci
            for (int i=0; i<blockDim; i++)
               dc_r += (input[above+i] & 0xFF);

            for (int j=start; j<endj; j+=st)
               dc_r += (input[j+blockDim] & 0xFF);

            dc_r = (dc_r + blockDim) / (blockDim + blockDim);
         }
      }

      if (x > 0)
         predictions[Mode.HORIZONTAL_L.value].energy = 0;

      if (x < xMax)
         predictions[Mode.HORIZONTAL_R.value].energy = 0;

      for (int j=start; j<endj; j+=st)
      {
         final int endi = j + blockDim;

         for (int i=j; i<endi; i+=8)
         {
            final int x0 = input[i]   & 0xFF;
            final int x1 = input[i+1] & 0xFF;
            final int x2 = input[i+2] & 0xFF;
            final int x3 = input[i+3] & 0xFF;
            final int x4 = input[i+4] & 0xFF;
            final int x5 = input[i+5] & 0xFF;
            final int x6 = input[i+6] & 0xFF;
            final int x7 = input[i+7] & 0xFF;

            if (y > 0)
            {
               final int px0 = input[i-st]   & 0xFF;
               final int px1 = input[i-st+1] & 0xFF;
               final int px2 = input[i-st+2] & 0xFF;
               final int px3 = input[i-st+3] & 0xFF;
               final int px4 = input[i-st+4] & 0xFF;
               final int px5 = input[i-st+5] & 0xFF;
               final int px6 = input[i-st+6] & 0xFF;
               final int px7 = input[i-st+7] & 0xFF;

               {
                  // VERTICAL: xi-ai
                  final int above = start - st;
                  final int idx = above + i - j;
                  final int val0 = x0 - (input[idx]   & 0xFF);
                  final int val1 = x1 - (input[idx+1] & 0xFF);
                  final int val2 = x2 - (input[idx+2] & 0xFF);
                  final int val3 = x3 - (input[idx+3] & 0xFF);
                  final int val4 = x4 - (input[idx+4] & 0xFF);
                  final int val5 = x5 - (input[idx+5] & 0xFF);
                  final int val6 = x6 - (input[idx+6] & 0xFF);
                  final int val7 = x7 - (input[idx+7] & 0xFF);
                  final Prediction p = predictions[Mode.VERTICAL.value];
                  final int[] output = p.residue;
                  output[k]   = val0;
                  output[k+1] = val1;
                  output[k+2] = val2;
                  output[k+3] = val3;
                  output[k+4] = val4;
                  output[k+5] = val5;
                  output[k+6] = val6;
                  output[k+7] = val7;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                               (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
               }

               if (x > 0)
               {
                  {
                     // DC_L: xi-dc_l
                     final int val0 = x0 - dc_l;
                     final int val1 = x1 - dc_l;
                     final int val2 = x2 - dc_l;
                     final int val3 = x3 - dc_l;
                     final int val4 = x4 - dc_l;
                     final int val5 = x5 - dc_l;
                     final int val6 = x6 - dc_l;
                     final int val7 = x7 - dc_l;
                     final Prediction p = predictions[Mode.DC_L.value];
                     final int[] output = p.residue;
                     output[k]   = val0;
                     output[k+1] = val1;
                     output[k+2] = val2;
                     output[k+3] = val3;
                     output[k+4] = val4;
                     output[k+5] = val5;
                     output[k+6] = val6;
                     output[k+7] = val7;
                     p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                                  (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
                  }

                  {
                     // AVERAGE_UL: (xi,yi)-avg((xi,yi-1),(xi-1,yi))
                     final int xx = input[i-1] & 0xFF;
                     int avg;
                     avg = (xx + px0) >> 1;
                     final int val0 = x0 - avg;
                     avg = (x0 + px1) >> 1;
                     final int val1 = x1 - avg;
                     avg = (x1 + px2) >> 1;
                     final int val2 = x2 - avg;
                     avg = (x2 + px3) >> 1;
                     final int val3 = x3 - avg;
                     avg = (x3 + px4) >> 1;
                     final int val4 = x4 - avg;
                     avg = (x4 + px5) >> 1;
                     final int val5 = x5 - avg;
                     avg = (x5 + px6) >> 1;
                     final int val6 = x6 - avg;
                     avg = (x6 + px7) >> 1;
                     final int val7 = x7 - avg;
                     final Prediction p = predictions[Mode.AVERAGE_UL.value];
                     final int[] output = p.residue;
                     output[k]   = val0;
                     output[k+1] = val1;
                     output[k+2] = val2;
                     output[k+3] = val3;
                     output[k+4] = val4;
                     output[k+5] = val5;
                     output[k+6] = val6;
                     output[k+7] = val7;
                     p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                                  (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
                  }
               }

               if (x < xMax)
               {
                  {
                     // DC_R: xi-dc_r
                     final int val0 = x0 - dc_r;
                     final int val1 = x1 - dc_r;
                     final int val2 = x2 - dc_r;
                     final int val3 = x3 - dc_r;
                     final int val4 = x4 - dc_r;
                     final int val5 = x5 - dc_r;
                     final int val6 = x6 - dc_r;
                     final int val7 = x7 - dc_r;
                     final Prediction p = predictions[Mode.DC_R.value];
                     final int[] output = p.residue;
                     output[k]   = val0;
                     output[k+1] = val1;
                     output[k+2] = val2;
                     output[k+3] = val3;
                     output[k+4] = val4;
                     output[k+5] = val5;
                     output[k+6] = val6;
                     output[k+7] = val7;
                     p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                                  (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
                  }

                  {
                     // AVERAGE_UR: (xi,yi)-avg((xi,yi-1),(xi+1,yi))
                     final int xx = input[i+8] & 0xFF;
                     int avg;
                     avg = (x1 + px0) >> 1;
                     final int val0 = x0 - avg;
                     avg = (x2 + px1) >> 1;
                     final int val1 = x1 - avg;
                     avg = (x3 + px2) >> 1;
                     final int val2 = x2 - avg;
                     avg = (x4 + px3) >> 1;
                     final int val3 = x3 - avg;
                     avg = (x5 + px4) >> 1;
                     final int val4 = x4 - avg;
                     avg = (x6 + px5) >> 1;
                     final int val5 = x5 - avg;
                     avg = (x7 + px6) >> 1;
                     final int val6 = x6 - avg;
                     avg = (xx + px7) >> 1;
                     final int val7 = x7 - avg;
                     final Prediction p = predictions[Mode.AVERAGE_UR.value];
                     final int[] output = p.residue;
                     output[k]   = val0;
                     output[k+1] = val1;
                     output[k+2] = val2;
                     output[k+3] = val3;
                     output[k+4] = val4;
                     output[k+5] = val5;
                     output[k+6] = val6;
                     output[k+7] = val7;
                     p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                                  (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
                  }
               }
            }

            if (x > 0)
            {
               // HORIZONTAL_L: xi-bi
               final int b = input[i-1] & 0xFF;
               final int val0 = x0 - b;
               final int val1 = x1 - b;
               final int val2 = x2 - b;
               final int val3 = x3 - b;
               final int val4 = x4 - b;
               final int val5 = x5 - b;
               final int val6 = x6 - b;
               final int val7 = x7 - b;
               final Prediction p = predictions[Mode.HORIZONTAL_L.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               output[k+4] = val4;
               output[k+5] = val5;
               output[k+6] = val6;
               output[k+7] = val7;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                            (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
            }

            if (x < xMax)
            {
               // HORIZONTAL_R: xi-ci
               final int c = input[i+blockDim] & 0xFF;
               final int val0 = x0 - c;
               final int val1 = x1 - c;
               final int val2 = x2 - c;
               final int val3 = x3 - c;
               final int val4 = x4 - c;
               final int val5 = x5 - c;
               final int val6 = x6 - c;
               final int val7 = x7 - c;
               final Prediction p = predictions[Mode.HORIZONTAL_R.value];
               final int[] output = p.residue;
               output[k]   = val0;
               output[k+1] = val1;
               output[k+2] = val2;
               output[k+3] = val3;
               output[k+4] = val4;
               output[k+5] = val5;
               output[k+6] = val6;
               output[k+7] = val7;
               p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                            (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
            }

            k += 8;
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

             for (int i=j; i<endi; i+=8)
             {
                output[j]   = q * residue[k];
                output[j+1] = q * residue[k+1];
                output[j+2] = q * residue[k+2];
                output[j+3] = q * residue[k+3];
                output[j+4] = q * residue[k+4];
                output[j+5] = q * residue[k+5];
                output[j+6] = q * residue[k+6];
                output[j+7] = q * residue[k+7];
                k += 8;
             }
          }
      }
      else
      {
         for (int j=oIdx; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
                output[i]   = q * residue[k]   + (input[iIdx]   & 0xFF);
                output[i+1] = q * residue[k+1] + (input[iIdx+1] & 0xFF);
                output[i+2] = q * residue[k+2] + (input[iIdx+2] & 0xFF);
                output[i+3] = q * residue[k+3] + (input[iIdx+3] & 0xFF);
                output[i+4] = q * residue[k+4] + (input[iIdx+4] & 0xFF);
                output[i+5] = q * residue[k+5] + (input[iIdx+5] & 0xFF);
                output[i+6] = q * residue[k+6] + (input[iIdx+6] & 0xFF);
                output[i+7] = q * residue[k+7] + (input[iIdx+7] & 0xFF);
                iIdx += 8;
                k += 8;
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
      int k = 0;

      if (mode == Mode.REFERENCE)
         return this.computeBlock(prediction, output, start, q, blockDim);

      if (mode == Mode.VERTICAL)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               final int r4 = q * residue[k+4];
               final int r5 = q * residue[k+5];
               final int r6 = q * residue[k+6];
               final int r7 = q * residue[k+7];
               // VERTICAL: xi+ai
               final int idx = start - st + i - j;
               output[i]   = r0 + (input[idx]   & 0xFF);
               output[i+1] = r1 + (input[idx+1] & 0xFF);
               output[i+2] = r2 + (input[idx+2] & 0xFF);
               output[i+3] = r3 + (input[idx+3] & 0xFF);
               output[i+4] = r4 + (input[idx+4] & 0xFF);
               output[i+5] = r5 + (input[idx+5] & 0xFF);
               output[i+6] = r6 + (input[idx+6] & 0xFF);
               output[i+7] = r7 + (input[idx+7] & 0xFF);
               k += 8;
            }
         }
      }
      else if (mode == Mode.AVERAGE_UL)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
               final int above = j - st;
               final int px0 = input[above]   & 0xFF;
               final int px1 = input[above+1] & 0xFF;
               final int px2 = input[above+2] & 0xFF;
               final int px3 = input[above+3] & 0xFF;
               final int px4 = input[above+4] & 0xFF;
               final int px5 = input[above+5] & 0xFF;
               final int px6 = input[above+6] & 0xFF;
               final int px7 = input[above+7] & 0xFF;
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               final int r4 = q * residue[k+4];
               final int r5 = q * residue[k+5];
               final int r6 = q * residue[k+6];
               final int r7 = q * residue[k+7];
               // AVERAGE_UL: (xi,yi)+avg((xi,yi-1),(xi-1,yi))
               final int xx = input[i-1] & 0xFF;
               final int x0 = r0 + ((xx + px0) >> 1);
               final int x1 = r1 + ((x0 + px1) >> 1);
               final int x2 = r2 + ((x1 + px2) >> 1);
               final int x3 = r3 + ((x2 + px3) >> 1);
               final int x4 = r4 + ((x3 + px4) >> 1);
               final int x5 = r5 + ((x4 + px5) >> 1);
               final int x6 = r6 + ((x5 + px6) >> 1);
               final int x7 = r7 + ((x6 + px7) >> 1);
               output[i]   = x0;
               output[i+1] = x1;
               output[i+2] = x2;
               output[i+3] = x3;
               output[i+4] = x4;
               output[i+5] = x5;
               output[i+6] = x6;
               output[i+7] = x7;
               k += 8;
            }
         }
      }
      else if (mode == Mode.AVERAGE_UR)
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
               final int above = j - st;
               final int px0 = input[above]   & 0xFF;
               final int px1 = input[above+1] & 0xFF;
               final int px2 = input[above+2] & 0xFF;
               final int px3 = input[above+3] & 0xFF;
               final int px4 = input[above+4] & 0xFF;
               final int px5 = input[above+5] & 0xFF;
               final int px6 = input[above+6] & 0xFF;
               final int px7 = input[above+7] & 0xFF;
               final int r0 = q * residue[k];
               final int r1 = q * residue[k+1];
               final int r2 = q * residue[k+2];
               final int r3 = q * residue[k+3];
               final int r4 = q * residue[k+4];
               final int r5 = q * residue[k+5];
               final int r6 = q * residue[k+6];
               final int r7 = q * residue[k+7];
               // AVERAGE_UR: (xi,yi)+avg((xi,yi-1),(xi+1,yi))
               final int xx = input[i+8] & 0xFF;
               final int x7 = r7 + ((xx + px7) >> 1);
               final int x6 = r6 + ((x7 + px6) >> 1);
               final int x5 = r5 + ((x6 + px5) >> 1);
               final int x4 = r4 + ((x5 + px4) >> 1);
               final int x3 = r3 + ((x4 + px3) >> 1);
               final int x2 = r2 + ((x3 + px2) >> 1);
               final int x1 = r1 + ((x2 + px1) >> 1);
               final int x0 = r0 + ((x1 + px0) >> 1);
               output[i]   = x0;
               output[i+1] = x1;
               output[i+2] = x2;
               output[i+3] = x3;
               output[i+4] = x4;
               output[i+5] = x5;
               output[i+6] = x6;
               output[i+7] = x7;
               k += 8;
            }
         }
      }
      else if ((mode == Mode.HORIZONTAL_L) || (mode == Mode.HORIZONTAL_R))
      {
         final int offs = (mode == Mode.HORIZONTAL_L) ? -1 : blockDim;

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
               // HORIZONTAL_L: xi+bi
               // HORIZONTAL_R: xi+ci
               final int val = input[i+offs] & 0xFF;
               output[i]   = (q * residue[k])   + val;
               output[i+1] = (q * residue[k+1]) + val;
               output[i+2] = (q * residue[k+2]) + val;
               output[i+3] = (q * residue[k+3]) + val;
               output[i+4] = (q * residue[k+4]) + val;
               output[i+5] = (q * residue[k+5]) + val;
               output[i+6] = (q * residue[k+6]) + val;
               output[i+7] = (q * residue[k+7]) + val;
               k += 8;
            }
         }
      }
      else if ((mode == Mode.DC_L) || (mode == Mode.DC_R))
      {
         final int dc;
         final int above = start - st;

         if (mode == Mode.DC_L)
         {
            int dc_l = 0;

            // dc=ai+bi
            for (int i=0; i<blockDim; i++)
               dc_l += (input[above+i] & 0xFF);

            for (int j=start; j<endj; j+=st)
               dc_l += (input[j-1] & 0xFF);

            dc = (dc_l + blockDim) / (blockDim + blockDim);
         }
         else
         {
            int dc_r = 0;

            // dc=ai+ci
            for (int i=0; i<blockDim; i++)
               dc_r += (input[above+i] & 0xFF);

            for (int j=start; j<endj; j+=st)
               dc_r += (input[j+blockDim] & 0xFF);

            dc = (dc_r + blockDim) / (blockDim + blockDim);
         }

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=8)
            {
               // DC_L: xi+dc_l
               // DC_R: xi+dc_r
               output[i]   = (q * residue[k])   + dc;
               output[i+1] = (q * residue[k+1]) + dc;
               output[i+2] = (q * residue[k+2]) + dc;
               output[i+3] = (q * residue[k+3]) + dc;
               output[i+4] = (q * residue[k+4]) + dc;
               output[i+5] = (q * residue[k+5]) + dc;
               output[i+6] = (q * residue[k+6]) + dc;
               output[i+7] = (q * residue[k+7]) + dc;
               k += 8;
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
      final int step = (blockDim >> this.spatialShift) * (blockDim >> 3);

      // Populate set of block candidates
      // Add blocks to compare against (blocks must already have been encoded/decoded
      // to avoid circular dependencies). Check against upper neighbors (current block
      // is XX):
      //    01 02 03 04 05
      //    06 07 08 09 10
      //    11 12 XX 13 14
      // step/candidates: 8=>14, 4=>39, 2=>125, 1=>441
      final int jstart = y - (blockDim << 1);

      for (int j=jstart; j<=y; j+=step)
      {
         if (j < 0)
            continue;

         final int istart = x - (blockDim << 1);
         final int iend = x + (blockDim << 1);

         for (int i=istart; i<=iend; i+=step)
         {
            if ((i < 0) || (i + blockDim >= this.width))
               continue;

            // Block candidates are not allowed to intersect with current block
            if ((j + blockDim > y) && (i + blockDim > x) && (i < x + blockDim))
               continue;

            this.set.add(BlockContext.getContext(prediction.frame, i, j, j*st+i));
         }
      }

      // Critical speed path
      while (this.set.size() > 0)
      {
         // Select partial winner (lowest energy) to update
         BlockContext ctx = this.set.pollFirst();

         // Full winner found ?
         if (ctx.line >= blockDim)
         {
            this.set.clear();
            prediction.x = ctx.x;
            prediction.y = ctx.y;
            prediction.energy = ctx.energy;
            return prediction.energy;
         }

         // Aliasing
         final int[] data = ctx.data;
         final int start = (y+ctx.line)*st + x;
         final int end = start + blockDim;
         int offs2 = (ctx.y+ctx.line)*st + ctx.x;
         int nrj = ctx.energy;

         // Compute line difference
         for (int i=start; i<end; i+=8)
         {
             final int val0 = (input[i]   & 0xFF) - (data[offs2]   & 0xFF);
             final int val1 = (input[i+1] & 0xFF) - (data[offs2+1] & 0xFF);
             final int val2 = (input[i+2] & 0xFF) - (data[offs2+2] & 0xFF);
             final int val3 = (input[i+3] & 0xFF) - (data[offs2+3] & 0xFF);
             final int val4 = (input[i+4] & 0xFF) - (data[offs2+4] & 0xFF);
             final int val5 = (input[i+5] & 0xFF) - (data[offs2+5] & 0xFF);
             final int val6 = (input[i+6] & 0xFF) - (data[offs2+6] & 0xFF);
             final int val7 = (input[i+7] & 0xFF) - (data[offs2+7] & 0xFF);
             nrj += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3) +
                     (val4*val4) + (val5*val5) + (val6*val6) + (val7*val7));
             offs2 += 8;
         }

         // Early exit, this block is not a good fit
         if (nrj >= maxEnergy)
            continue;

         // Put back current block context into sorted set (likely new position)
         ctx.energy = nrj;
         ctx.line++;
         this.set.add(ctx);
      }

      return MAX_VALUE;
   }


   private static class BlockContext implements Comparable<BlockContext>
   {
      int startOffs; // offset of block's top left corner in frame
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


      public static BlockContext getContext(int[] data, int x, int y, int startOffs)
      {
         BlockContext res = CACHE[INDEX];

         if (++INDEX == CACHE.length)
            INDEX = 0;

         res.energy = 0;
         res.data = data;
         res.line = 0;
         res.startOffs = startOffs;
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

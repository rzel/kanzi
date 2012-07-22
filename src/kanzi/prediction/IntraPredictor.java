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
import kanzi.InputBitStream;
import kanzi.OutputBitStream;


// Class used to predict a block based on its neighbors in the frame
public class IntraPredictor
{
   public enum Mode
   {
      AVERAGE_HV(0, 3),  // Average horizontal-vertical
      DC(1, 3),          // DC
      HORIZONTAL(2, 3),  // Horizontal
      VERTICAL(3, 3),    // Vertical (top)
      REFERENCE(4, 3);   // Other block used as reference

      // actual intra mode block can be encoded as reference prediction mode
      // with same frame and deltaX=deltaY=0

      private final byte value;
      private final byte length; // code length in bits

      Mode(int value, int length)
      {
         this.value = (byte) value;
         this.length = (byte) length;
      }

      public int value()
      {
         return this.value;
      }

      public static Mode getMode(int val)
      {
         if (val == AVERAGE_HV.value)
            return AVERAGE_HV;

         if (val == DC.value)
            return DC;

         if (val == HORIZONTAL.value)
            return HORIZONTAL;

         if (val == VERTICAL.value)
            return VERTICAL;

         if (val == REFERENCE.value)
            return REFERENCE;

         return null;
      }

      public int encode(OutputBitStream bs)
      {
         if (bs.writeBits(this.value, this.length) != this.length)
            return -1;

         return this.length;
      }

      public static Mode decode(InputBitStream bs)
      {
         int bit = bs.readBit();

         if (bit == 0)
            return getMode((bit << 1) | bs.readBit());

         return getMode((int) ((bit << 3) | bs.readBits(3)));
      }
   };

   private static final int ACTION_POPULATE  = 1;
   private static final int ACTION_GET_INDEX = 2;
   private static final int ACTION_GET_COORD = 3;

   public static final int DIR_LEFT  = 1;
   public static final int DIR_RIGHT = 2;
   public static final int REFERENCE = 4;

   public static final int MAX_ERROR = 1 << 26; // Not Integer.Max to avoid add overflow
   private static final int DEFAULT_VALUE = 128;

   private final int width;
   private final int height;
   private final int stride;
   private final TreeSet<SearchBlockContext> set; // used during reference search
   private final int sqrErrThreshold; // used to trigger reference search
   private final int logRefSearchStepRatio;
   private final boolean isRGB;
   private final int maxBlockDim;


   public IntraPredictor(int width, int height, int maxBlockDim)
   {
      this(width, height, width, maxBlockDim, true);
   }


   public IntraPredictor(int width, int height, int maxBlockDim, int stride, boolean isRGB)
   {
      this(width, height, maxBlockDim, stride, isRGB, 5);
   }


   public IntraPredictor(int width, int height, int maxBlockDim, int stride,
           boolean isRGB, int errThreshold)
   {
      this(width, height, maxBlockDim, stride, isRGB, errThreshold, 4);
   }


   // errThreshold is the residue energy per pixel that would trigger a spatial
   // search for neighbor blocks. It is checked at the end of the 1st step of
   // prediction to possibly trigger a 2nd step (if the residue energy is too high).
   // a value of 0 means that the spatial search happens always (except if the
   // residue energy per pixel is 0 at the end of step 1)
   // a value of 256 means that the spatial search never happens.
   // refSearchStepRatio can be 1,2 or 4. It says what the reference search step
   // size is compared to the block dimension: 1, 1/2, 1/4 or 1/8 (except if block
   // dim is 4)
   public IntraPredictor(int width, int height, int maxBlockDim, int stride,
           boolean isRGB, int errThreshold, int refSearchStepRatio)
   {
     if (height < 8)
        throw new IllegalArgumentException("The height must be at least 8");

     if (width < 8)
        throw new IllegalArgumentException("The width must be at least 8");

     if (stride < 8)
        throw new IllegalArgumentException("The stride must be at least 8");

     if ((height & 3) != 0)
        throw new IllegalArgumentException("The height must be a multiple of 4");

     if ((width & 3) != 0)
        throw new IllegalArgumentException("The width must be a multiple of 4");

     if ((stride & 3) != 0)
        throw new IllegalArgumentException("The stride must be a multiple of 4");

     if ((maxBlockDim < 4) || (maxBlockDim > 64))
        throw new IllegalArgumentException("The maximum block dimension must be in the [4..64] range"); // for now

     if ((maxBlockDim & 3) != 0)
        throw new IllegalArgumentException("The maximum block dimension must be a multiple of 4");

     if ((errThreshold < 0) || (errThreshold > 256))
        throw new IllegalArgumentException("The residue energy threshold per pixel must in [0..256]");

     if ((refSearchStepRatio != 1) && (refSearchStepRatio != 2) &&
             (refSearchStepRatio != 4) && (refSearchStepRatio != 8))
        throw new IllegalArgumentException("The reference search step ratio must "
                + "be in [1,1/2,1/4,1/8] of the block dimension");

     this.height = height;
     this.width = width;
     this.stride = stride;
     this.set = new TreeSet<SearchBlockContext>();
     this.sqrErrThreshold = errThreshold * errThreshold;
     this.maxBlockDim = maxBlockDim;
     this.isRGB = isRGB;
     int log = 0;

     for ( ; refSearchStepRatio>1; refSearchStepRatio>>=1)
       log++;

     this.logRefSearchStepRatio = log;
   }


   // Compute block prediction (from other blocks) using several different methods (modes)
   // Another block (spatial or temporal) can be provided optionally
   // The input arrays must be frame channels (R,G,B or Y,U,V)
   // input is a block in a frame at offset iy*stride+ix
   // output is the difference block (a.k.a residual block)
   // return energy for each prediction mode
   public Prediction[] computeResidues(int[] input, int ix, int iy,
           int[] other, int ox, int oy,
           Prediction[] predictions, int blockDim, int reference)
   {
      if ((ix < 0) || (ix >= this.width) || (iy < 0) || (iy >= this.height))
         return null;

      // The block dimension must be a multiple of 4
      if ((blockDim & 3) != 0)
         return null;

      // Check block dimension
      if (blockDim > this.maxBlockDim)
         return null;

      // Reference cannot be empty
      if (((reference & DIR_RIGHT) == 0) && ((reference & DIR_LEFT) == 0) && ((reference & REFERENCE) == 0))
         return null;

      // Both directions at the same time are not allowed
      if (((reference & DIR_RIGHT) != 0) && ((reference & DIR_LEFT) != 0))
         return null;

      int minIdx = 0;

      // Intialize predictions
      for (Prediction p : predictions)
      {
         p.energy = MAX_ERROR;
         p.x = ix;
         p.y = iy;
         p.frame = input;
         p.blockDim = blockDim;
      }

      // Start with spatial/temporal reference (if any)
      if (other != null)
      {
         Prediction p = predictions[Mode.REFERENCE.value];
         p.frame = other;
         p.x = ox;
         p.y = oy;
         p.energy = this.computeReferenceDiff(input, iy*this.stride+ix,
                 other, oy*this.stride+ox, p.residue, blockDim);

         if (p.energy == 0)
           return predictions;

         minIdx = Mode.REFERENCE.value;
      }

      // Compute block residues based on prediction modes
      this.computePredictionDiff(input, ox, oy, predictions, blockDim, reference);

      // Find best prediction
      for (int i=0; i<predictions.length; i++)
      {
         // Favor lower modes (less bits to encode)
         if (predictions[minIdx].energy > predictions[i].energy)
            minIdx = i;
      }

      final int minNrj = predictions[minIdx].energy;

      if (minNrj == 0)
         return predictions;

      // If the energy of the best prediction is not low 'enough' and the
      // spatial reference is set, start a spatial search
      if (((reference & REFERENCE) != 0) && (iy > 0) && (minNrj >= blockDim * blockDim * this.sqrErrThreshold))
      {
         // Spatial search of best matching nearby block
         Prediction newPrediction = new Prediction(input, 0, 0, blockDim);

         // Do the search and update prediction energy, coordinates and result block
         this.computeReferenceSearch(input, ix, iy, minNrj, newPrediction, reference);

         // Is the new prediction an improvement ?
         if (newPrediction.energy < predictions[Mode.REFERENCE.value].energy)
         {
            Prediction refPred = predictions[Mode.REFERENCE.value];
            refPred.x = newPrediction.x;
            refPred.y = newPrediction.y;
            refPred.energy = newPrediction.energy;

            // Create residue block for reference mode
            this.computeReferenceDiff(input, iy*this.stride+ix, input,
                    newPrediction.y*this.stride+newPrediction.x,
                    newPrediction.residue, blockDim);

            System.arraycopy(newPrediction.residue, 0, refPred.residue, 0, newPrediction.residue.length);
         }
      }

      return predictions;
   }


   // Compute residue against another (spatial/temporal) block
   // Return energy of difference block
   private int computeReferenceDiff(int[] input, int iIdx, int[] other, int oIdx,
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

         // Block delta
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
   // Return energy of residual block
   // Proceed line by line (for cache) and avoid branches when possible (for speed)
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
   private Prediction[] computePredictionDiff(int[] input, int x, int y,
           Prediction[] predictions, int blockDim, int direction)
   {
      if (((direction & DIR_LEFT) == 0) && ((direction & DIR_RIGHT) == 0))
         return predictions;

      final int w = this.width;
      final int xMax = w - blockDim;
      final int st = this.stride;
      final int start = y*st + x;
      final int endj = start + (st * blockDim);
      final int mask = (this.isRGB == true) ? 0xFF : -1;
      int line = 0;
      int idx_l = 0;
      int dc_l = 0;
      int dc_r = 0;
      int sum_l = 0;
      int sum_r = 0;

      // Initializations
      if ((direction & DIR_LEFT) != 0)
      {
         predictions[Mode.HORIZONTAL.value].energy = 0;
         predictions[Mode.DC.value].energy = 0;
         predictions[Mode.AVERAGE_HV.value].energy = 0;

         if (x > 0)
         {
            sum_l += blockDim;

            for (int j=start; j<endj; j+=st)
               dc_l += (input[j-1] & mask);
         }
      }

      if ((direction & DIR_RIGHT) != 0)
      {
         predictions[Mode.HORIZONTAL.value].energy = 0;
         predictions[Mode.DC.value].energy = 0;
         predictions[Mode.AVERAGE_HV.value].energy = 0;

         if (x < xMax)
         {
            sum_r += blockDim;

            for (int j=start; j<endj; j+=st)
               dc_r += (input[j+blockDim] & mask);
         }
      }

      if (y > 0)
      {
         predictions[Mode.VERTICAL.value].energy = 0;
         final int above = start - st;

         if ((direction & DIR_LEFT) != 0)
         {
            sum_l += blockDim;

            for (int i=0; i<blockDim; i++)
               dc_l += (input[above+i] & mask);
         }

         if ((direction & DIR_RIGHT) != 0)
         {
            sum_r += blockDim;

            for (int i=0; i<blockDim; i++)
               dc_r += (input[above+i] & mask);
         }
      }

      if (sum_l != 0)
         dc_l = (dc_l + (sum_l >> 1)) / sum_l;
      else
         dc_l = DEFAULT_VALUE;

      if (sum_r != 0)
         dc_r = (dc_r + (sum_r >> 1)) / sum_r;
      else
         dc_r = DEFAULT_VALUE;

      // Main loop, line by line
      for (int j=start; j<endj; j+=st)
      {
         final int endi = j + blockDim;

         if ((direction & DIR_LEFT) != 0)
         {
            final int b = (x > 0) ? input[j-1] & mask : DEFAULT_VALUE;

            // Scan from the left to the right
            for (int i=j; i<endi; i+=4)
            {
               final int x0 = input[i]   & mask;
               final int x1 = input[i+1] & mask;
               final int x2 = input[i+2] & mask;
               final int x3 = input[i+3] & mask;
               int a0, a1, a2, a3;

               if (y > 0)
               {
                  final int blockAbove = i - j + start - st;
                  a0 = input[blockAbove]   & mask;
                  a1 = input[blockAbove+1] & mask;
                  a2 = input[blockAbove+2] & mask;
                  a3 = input[blockAbove+3] & mask;
               }
               else
               {
                  a0 = DEFAULT_VALUE;
                  a1 = DEFAULT_VALUE;
                  a2 = DEFAULT_VALUE;
                  a3 = DEFAULT_VALUE;
               }

               {
                  // HORIZONTAL_L: xi-bi
                  final int val0 = x0 - b;
                  final int val1 = x1 - b;
                  final int val2 = x2 - b;
                  final int val3 = x3 - b;
                  final Prediction p = predictions[Mode.HORIZONTAL.value];
                  final int[] output = p.residue;
                  output[idx_l]   = val0;
                  output[idx_l+1] = val1;
                  output[idx_l+2] = val2;
                  output[idx_l+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               {
                  // AVERAGE_HV_L: (xi,yi)-avg(ai,bi)
                  int avg;
                  avg = (b + a0) >> 1;
                  final int val0 = x0 - avg;
                  avg = (b + a1) >> 1;
                  final int val1 = x1 - avg;
                  avg = (b + a2) >> 1;
                  final int val2 = x2 - avg;
                  avg = (b + a3) >> 1;
                  final int val3 = x3 - avg;
                  final Prediction p = predictions[Mode.AVERAGE_HV.value];
                  final int[] output = p.residue;
                  output[idx_l]   = val0;
                  output[idx_l+1] = val1;
                  output[idx_l+2] = val2;
                  output[idx_l+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               {
                  // DC_L: xi-dc_l
                  final int val0 = x0 - dc_l;
                  final int val1 = x1 - dc_l;
                  final int val2 = x2 - dc_l;
                  final int val3 = x3 - dc_l;
                  final Prediction p = predictions[Mode.DC.value];
                  final int[] output = p.residue;
                  output[idx_l]   = val0;
                  output[idx_l+1] = val1;
                  output[idx_l+2] = val2;
                  output[idx_l+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               if (y > 0)
               {
                  // VERTICAL: xi-ai
                  final int val0 = x0 - a0;
                  final int val1 = x1 - a1;
                  final int val2 = x2 - a2;
                  final int val3 = x3 - a3;
                  final Prediction p = predictions[Mode.VERTICAL.value];
                  final int[] output = p.residue;
                  output[idx_l]   = val0;
                  output[idx_l+1] = val1;
                  output[idx_l+2] = val2;
                  output[idx_l+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               idx_l += 4;
            }
         } // LEFT

         if ((direction & DIR_RIGHT) != 0)
         {
            final int c = (x < xMax) ? input[endi] & mask : DEFAULT_VALUE;

            // Scan from right to left
            for (int i=endi-4; i>=j; i-=4)
            {
               final int idx_r = line + i - j;
               final int x0 = input[i]   & mask;
               final int x1 = input[i+1] & mask;
               final int x2 = input[i+2] & mask;
               final int x3 = input[i+3] & mask;
               int a0, a1, a2, a3;

               if (y > 0)
               {
                  final int blockAbove = i - j + start - st;
                  a0 = input[blockAbove]   & mask;
                  a1 = input[blockAbove+1] & mask;
                  a2 = input[blockAbove+2] & mask;
                  a3 = input[blockAbove+3] & mask;
               }
               else
               {
                  a0 = DEFAULT_VALUE;
                  a1 = DEFAULT_VALUE;
                  a2 = DEFAULT_VALUE;
                  a3 = DEFAULT_VALUE;
               }

               {
                  // HORIZONTAL_R: xi-ci
                  final int val0 = x0 - c;
                  final int val1 = x1 - c;
                  final int val2 = x2 - c;
                  final int val3 = x3 - c;
                  final Prediction p = predictions[Mode.HORIZONTAL.value];
                  final int[] output = p.residue;
                  output[idx_r]   = val0;
                  output[idx_r+1] = val1;
                  output[idx_r+2] = val2;
                  output[idx_r+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               {
                  // AVERAGE_HV_R: (xi,yi)-avg(ai, ci)
                  int avg;
                  avg = (c + a0) >> 1;
                  final int val0 = x0 - avg;
                  avg = (c + a1) >> 1;
                  final int val1 = x1 - avg;
                  avg = (c + a2) >> 1;
                  final int val2 = x2 - avg;
                  avg = (c + a3) >> 1;
                  final int val3 = x3 - avg;
                  final Prediction p = predictions[Mode.AVERAGE_HV.value];
                  final int[] output = p.residue;
                  output[idx_r]   = val0;
                  output[idx_r+1] = val1;
                  output[idx_r+2] = val2;
                  output[idx_r+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               {
                  // DC_R: xi-dc_r
                  final int val0 = x0 - dc_r;
                  final int val1 = x1 - dc_r;
                  final int val2 = x2 - dc_r;
                  final int val3 = x3 - dc_r;
                  final Prediction p = predictions[Mode.DC.value];
                  final int[] output = p.residue;
                  output[idx_r]   = val0;
                  output[idx_r+1] = val1;
                  output[idx_r+2] = val2;
                  output[idx_r+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }

               if (y > 0)
               {
                  // VERTICAL: xi-ai
                  final int val0 = x0 - a0;
                  final int val1 = x1 - a1;
                  final int val2 = x2 - a2;
                  final int val3 = x3 - a3;
                  final Prediction p = predictions[Mode.VERTICAL.value];
                  final int[] output = p.residue;
                  output[idx_r]   = val0;
                  output[idx_r+1] = val1;
                  output[idx_r+2] = val2;
                  output[idx_r+3] = val3;
                  p.energy += ((val0*val0) + (val1*val1) + (val2*val2) + (val3*val3));
               }
            }

            line += blockDim;
         } // RIGHT
      } // y loop

      return predictions;
   }


   // Add residue to other block
   // Return output
   private int[] computeBlock(Prediction prediction, int[] output, int oIdx)
   {
      final int st = this.stride;
      final int blockDim = prediction.blockDim;
      final int endj = oIdx + (st * blockDim);
      final int[] residue = prediction.residue;
      final int[] input = prediction.frame;
      int iIdx = (prediction.y * st) + prediction.x;
      int k = 0;

      if (input == null)
      {
          // Simple copy
          for (int j=oIdx; j<endj; j+=st)
          {
             final int endi = j + blockDim;

             for (int i=j; i<endi; i+=4)
             {
                output[j]   = residue[k];
                output[j+1] = residue[k+1];
                output[j+2] = residue[k+2];
                output[j+3] = residue[k+3];
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
                output[i]   = residue[k]   + (input[iIdx]   & mask);
                output[i+1] = residue[k+1] + (input[iIdx+1] & mask);
                output[i+2] = residue[k+2] + (input[iIdx+2] & mask);
                output[i+3] = residue[k+3] + (input[iIdx+3] & mask);
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
           Mode mode, int direction)
   {
      final int w = this.width;
      final int blockDim = prediction.blockDim;
      final int xMax = w - blockDim;
      final int st = this.stride;
      final int start = (y * st) + x;
      final int endj = start + (st * blockDim);
      final int[] residue = prediction.residue;
      final int[] input = prediction.frame;
      final int mask = (this.isRGB == true) ? 0xFF : -1;
      int k = 0;

      if (mode == Mode.REFERENCE)
         return this.computeBlock(prediction, output, start);

      if (mode == Mode.VERTICAL)
      {
         if ((direction & DIR_LEFT) != 0)
         {
            for (int j=start; j<endj; j+=st)
            {
               final int endi = j + blockDim;

               for (int i=j; i<endi; i+=4)
               {
                  int a0, a1, a2, a3;

                  // VERTICAL: xi+ai
                  if (y > 0)
                  {
                     final int blockAbove = i - j + start - st;
                     a0 = input[blockAbove]   & mask;
                     a1 = input[blockAbove+1] & mask;
                     a2 = input[blockAbove+2] & mask;
                     a3 = input[blockAbove+3] & mask;
                  }
                  else
                  {
                     a0 = DEFAULT_VALUE;
                     a1 = DEFAULT_VALUE;
                     a2 = DEFAULT_VALUE;
                     a3 = DEFAULT_VALUE;
                  }

                  output[i]   = residue[k]   + a0;
                  output[i+1] = residue[k+1] + a1;
                  output[i+2] = residue[k+2] + a2;
                  output[i+3] = residue[k+3] + a3;
                  k += 4;
               }
            }
         }
         else
         {
            int line = 0;

            for (int j=start; j<endj; j+=st)
            {
               final int endi = j + blockDim;

               for (int i=endi-4; i>=j ; i-=4)
               {
                  k = line + i - j;
                  int a0, a1, a2, a3;

                  // VERTICAL: xi+ai
                  if (y > 0)
                  {
                     final int blockAbove = i - j + start - st;
                     a0 = input[blockAbove]   & mask;
                     a1 = input[blockAbove+1] & mask;
                     a2 = input[blockAbove+2] & mask;
                     a3 = input[blockAbove+3] & mask;
                  }
                  else
                  {
                     a0 = DEFAULT_VALUE;
                     a1 = DEFAULT_VALUE;
                     a2 = DEFAULT_VALUE;
                     a3 = DEFAULT_VALUE;
                  }

                  output[i]   = residue[k]   + a0;
                  output[i+1] = residue[k+1] + a1;
                  output[i+2] = residue[k+2] + a2;
                  output[i+3] = residue[k+3] + a3;
               }

               line += blockDim;
            }
         }
      }
      else if ((mode == Mode.AVERAGE_HV) && ((direction & DIR_LEFT) != 0))
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
               final int b = (x > 0) ? input[j-1] & mask : DEFAULT_VALUE;
               int a0, a1, a2, a3;

               if (y > 0)
               {
                  final int blockAbove = start - st + i - j;
                  a0 = input[blockAbove]   & mask;
                  a1 = input[blockAbove+1] & mask;
                  a2 = input[blockAbove+2] & mask;
                  a3 = input[blockAbove+3] & mask;
               }
               else
               {
                  a0 = DEFAULT_VALUE;
                  a1 = DEFAULT_VALUE;
                  a2 = DEFAULT_VALUE;
                  a3 = DEFAULT_VALUE;
               }

               // AVERAGE_HV_L: (xi,yi)+avg(ai, bi)
               output[i]   = residue[k]   + ((b + a0) >> 1);
               output[i+1] = residue[k+1] + ((b + a1) >> 1);
               output[i+2] = residue[k+2] + ((b + a2) >> 1);
               output[i+3] = residue[k+3] + ((b + a3) >> 1);
               k += 4;
            }
         }
      }
      else if ((mode == Mode.AVERAGE_HV) && ((direction & DIR_RIGHT) != 0))
      {
         int line = 0;

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=endi-4; i>=j ; i-=4)
            {
               final int c = (x < xMax) ? input[endi] & mask : DEFAULT_VALUE;
               k = line + i - j;
               int a0, a1, a2, a3;

               if (y > 0)
               {
                  final int blockAbove = start - st + i - j;
                  a0 = input[blockAbove]   & mask;
                  a1 = input[blockAbove+1] & mask;
                  a2 = input[blockAbove+2] & mask;
                  a3 = input[blockAbove+3] & mask;
               }
               else
               {
                  a0 = DEFAULT_VALUE;
                  a1 = DEFAULT_VALUE;
                  a2 = DEFAULT_VALUE;
                  a3 = DEFAULT_VALUE;
               }

               // AVERAGE_HV_R: (xi,yi)+avg(bi, ci)
               output[i]   = residue[k]   + ((c + a0) >> 1);
               output[i+1] = residue[k+1] + ((c + a1) >> 1);
               output[i+2] = residue[k+2] + ((c + a2) >> 1);
               output[i+3] = residue[k+3] + ((c + a3) >> 1);
            }

            line += blockDim;
         }
      }
      else if ((mode == Mode.HORIZONTAL) && ((direction & DIR_LEFT) != 0))
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;
            final int b = (x > 0) ? input[j-1] & mask : DEFAULT_VALUE;

            for (int i=j; i<endi; i+=4)
            {
               // HORIZONTAL_L: xi+bi
               output[i]   = residue[k]   + b;
               output[i+1] = residue[k+1] + b;
               output[i+2] = residue[k+2] + b;
               output[i+3] = residue[k+3] + b;
               k += 4;
            }
         }
      }
      else if ((mode == Mode.HORIZONTAL) && ((direction & DIR_RIGHT) != 0))
      {
         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;
            final int c = (x < xMax) ? input[endi] & mask : DEFAULT_VALUE;

            for (int i=j; i<endi; i+=4)
            {
               // HORIZONTAL_R: xi+ci
               output[i]   = residue[k]   + c;
               output[i+1] = residue[k+1] + c;
               output[i+2] = residue[k+2] + c;
               output[i+3] = residue[k+3] + c;
               k += 4;
            }
         }
      }
      else if (mode == Mode.DC)
      {
         final int dc;
         final int above = start - st;

         if ((direction & DIR_LEFT) != 0)
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

            dc = (sum == 0) ? DEFAULT_VALUE : (dc_l + (sum >> 1)) / sum;
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

            dc = (sum == 0) ? DEFAULT_VALUE : (dc_r + (sum >> 1)) / sum;
         }

         for (int j=start; j<endj; j+=st)
         {
            final int endi = j + blockDim;

            for (int i=j; i<endi; i+=4)
            {
               // DC_L: xi+dc_l
               // DC_R: xi+dc_r
               output[i]   = residue[k]   + dc;
               output[i+1] = residue[k+1] + dc;
               output[i+2] = residue[k+2] + dc;
               output[i+3] = residue[k+3] + dc;
               k += 4;
            }
         }
      }

      return output;
   }


   // Search for a similar block that can be used as reference
   // Base prediction on difference with nearby blocks using 'winner update' strategy
   // Return energy and update prediction argument
   private int computeReferenceSearch(int[] input, int x, int y,
           int maxEnergy, Prediction prediction, int direction)
   {
      final int blockDim = prediction.blockDim;

      // Populate the set of neighboring candidate blocks
      this.getReferenceSearchBlocks(x, y, blockDim, 0, 0, direction,
              prediction.frame, ACTION_POPULATE, 0);

      SearchBlockContext ctx = null;
      final int mask = (this.isRGB == true) ? 0xFF : -1;
      final int st = this.stride;

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
         return MAX_ERROR;

      // Return best result
      prediction.x = ctx.x;
      prediction.y = ctx.y;
      prediction.energy = ctx.energy;
      return prediction.energy;
   }


   // (x,y) coordinates of current block
   // (xr, yr) coordinates of reference block
   // return (index << 16 | nbOfPositions)
   public int getReferenceIndexFromPosition(int x, int y, int blockDim,
           int xr, int yr, int direction)
   {
      return this.getReferenceSearchBlocks(x, y, blockDim, xr, yr, direction,
              null, ACTION_GET_INDEX, 0);
   }


   // return (x << 16 | y)
   public int getReferencePositionFromIndex(int x, int y, int blockDim,
           int direction, int index)
   {
      return this.getReferenceSearchBlocks(x, y, blockDim, 0, 0, direction,
              null, ACTION_GET_COORD, index);
   }


   // if action == ACTION_POPULATE, populate the set of search blocks and return
   // the size of the set
   // if action == ACTION_GET_INDEX, return the index of the block based on the
   // provided coordinates
   // if action == ACTION_GET_COORD, return the coordinates of the block based on
   // the provided index
   private int getReferenceSearchBlocks(int x, int y, int blockDim, int xr, int yr,
           int direction, int[] referenceFrame, int action, int refIndex)
   {
      int step = blockDim >> this.logRefSearchStepRatio;

      // Address case where blockDim == 4 and refSearchStepRatio == 1/8
      if (step == 0)
         step = 1;

      // Populate set of block candidates
      // Add blocks to compare against (blocks must already have been encoded/decoded
      // to avoid circular dependencies). Check against upper neighbors (current block
      // is XX):
      //    LEFT+RIGHT        LEFT              RIGHT
      //    01 02 03 04 05    01 02 03 04 05    01 02 03 04 05
      //    06 07 08 09 10    06 07 08 09 10    06 07 08 09 10
      //       11 XX 12          11 XX                XX 11
      // for dim!=4, LEFT+RIGHT steps=>candidates: dim/1=>12, dim/2=>35, dim/4=>117, dim/8=>425
      // for dim=4,  LEFT+RIGHT steps=>candidates: dim/1=>12, dim/2=>35, dim/4=>117, dim/8=>117
      // LEFT or RIGHT = candidates(LEFT+RIGHT) - 1
      final int jstart = y - (blockDim << 1);
      int val = -1;

      for (int j=jstart; j<=y; j+=step)
      {
         if (j < 0)
            continue;

         final int istart = (j < y) ? x - (blockDim << 1) :
            (((direction & DIR_LEFT) != 0) ? x - blockDim : x + blockDim);
         final int iend = (j < y) ? x + (blockDim << 1) :
            (((direction & DIR_RIGHT) != 0) ? x + blockDim : x - blockDim);

         for (int i=istart; i<=iend; i+=step)
         {
            if ((i < 0) || (i + blockDim >= this.width))
               continue;

            // Block candidates are not allowed to intersect with current block
            if ((j + blockDim > y) && (i + blockDim > x) && (i < x + blockDim))
               continue;

            if (action == ACTION_POPULATE)
            {
               // Add to set sorted by residual energy and coordinates
               this.set.add(SearchBlockContext.getContext(referenceFrame, i, j));
            }
            else if (action == ACTION_GET_INDEX)
            {
               val++;

               if ((i == xr) && (j == yr))
               {
                  final int adjust = (((direction & DIR_LEFT) != 0) &&
                          ((direction & DIR_RIGHT) != 0)) ? 1 : 0;
                  int positions = 0;

                  if (this.logRefSearchStepRatio == 0)
                     positions = 11 + adjust;
                  else if(this.logRefSearchStepRatio == 1)
                     positions = 34 + adjust;
                  else if ((this.logRefSearchStepRatio == 2) || (blockDim == 4))
                     positions = 116 + adjust;
                  else if (this.logRefSearchStepRatio == 3)
                     positions = 424 + adjust;

                  return (val << 16) | positions;
               }
            }
            else if (action == ACTION_GET_COORD)
            {
               val++;

               if (refIndex == val)
                  return (i<<16) | j;
            }
         }
      }

      if (action == ACTION_POPULATE)
         val = this.set.size();

      return val;
   }



   private static class SearchBlockContext implements Comparable<SearchBlockContext>
   {
      int line;      // line to be processed
      int energy;    // energy so far
      int[] data;    // frame data
      int x;
      int y;

      private static SearchBlockContext[] CACHE = init();
      private static int INDEX = 0;

      private static SearchBlockContext[] init()
      {
         SearchBlockContext[] res = new SearchBlockContext[425]; // max block candidates per call

         for (int i=0; i<res.length; i++)
            res[i] = new SearchBlockContext();

         return res;
      }


      public static SearchBlockContext getContext(int[] data, int x, int y)
      {
         SearchBlockContext res = CACHE[INDEX];

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
      public int compareTo(SearchBlockContext c)
      {
         if (c == null)
            return 1;

         if (this.energy != c.energy)
            return this.energy - c.energy;

         if (this.y != c.y)
            return this.y - c.y;

         return this.x - c.x;
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

            SearchBlockContext c = (SearchBlockContext) o;

            if (this.energy != c.energy)
               return false;

            if (this.y != c.y)
               return false;

            if (this.x != c.x)
               return false;

            return true;
         }
         catch (ClassCastException e)
         {
            return false;
         }
      }
   }


   public static class Prediction
   {
      public int blockDim;
      public int energy;
      public int[] frame;
      public int[] residue;
      public int x;
      public int y;


      public Prediction(int maxBlockDim)
      {
         if ((maxBlockDim < 4) || (maxBlockDim > 64))
            throw new IllegalArgumentException("The maximum block dimension must be in the [4..64] range"); // for now

         if ((maxBlockDim & 7) != 0)
            throw new IllegalArgumentException("The maximum block dimension must be a multiple of 8");

         this.residue = new int[maxBlockDim*maxBlockDim];
         this.blockDim = maxBlockDim;
      }


      public Prediction(int[] frame, int x, int y, int blockDim)
      {
         this.frame = frame;
         this.x = x;
         this.y = y;
         this.residue = new int[blockDim*blockDim];
         this.blockDim = blockDim;
      }
   }
}

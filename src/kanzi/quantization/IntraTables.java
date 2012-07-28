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

package kanzi.quantization;

public class IntraTables
{
   public static final int FLAT = 0;
   public static final int BALANCED = 1;
   public static final int STEEP = 2;

   public static final IntraTables SINGLETON = new IntraTables();

   private static final int[] QSCALE = new int[]
   {
       32,   38,   46,   55,   66,   79,   95,  114,
      137,  165,  198,  237,  285,  342,  410,  493,
      591,  709,  851, 1022, 1226, 1472, 1766, 2119,
     2543, 3052, 3663, 4395, 5275, 6330, 7596, 9115
   }; // step in 1/32th

   private static final int[] FLAT_4x4 =
           new int[]
   {
      32, 38, 32, 38,
      38, 43, 38, 43,
      32, 38, 32, 38,
      38, 43, 38, 43
   };

   private static final int[] BALANCED_4x4 =
           new int[]
   {
      32, 40, 32, 40,
      40, 50, 40, 50,
      32, 40, 32, 40,
      40, 50, 40, 50
   };

   private static final int[] STEEP_4x4 =
           new int[]
   {
      32,  60, 40,  60,
      60, 100, 60, 100,
      40,  60, 40,  60,
      60, 100, 60, 100
   };

 
   private static final int[][][] TABLES_4x4 =
   {
      // LUMA
      new int[][] { FLAT_4x4, BALANCED_4x4, STEEP_4x4 },
      // CHROMA U
      new int[][] { BALANCED_4x4, STEEP_4x4, STEEP_4x4 },
      // CHROMA V
      new int[][] { BALANCED_4x4, STEEP_4x4, STEEP_4x4 }
   };


   private static final int[][][] TABLES_8x8 =
   {
      // LUMA
      new int[][]
      {
         // FLAT
         new int[]
         {
            32,  30,  29,  32,  38,  64,  81,  97,
            29,  29,  31,  36,  41,  89,  96,  88,
            31,  29,  32,  42,  64,  91, 110,  89,
            32,  33,  38,  46,  81, 139, 128,  99,
            35,  35,  59,  92, 108, 174, 164, 123,
            38,  56,  88, 102, 129, 166, 180, 147,
            78, 102, 124, 139, 164, 193, 192, 161,
           115, 147, 152, 156, 179, 160, 164, 158
         },
         // BALANCED
         new int[]
         {
            32,  29,  28,  32,  48,  80, 102, 122,
            28,  28,  30,  38,  52, 116, 120, 110,
            30,  28,  32,  48,  80, 114, 138, 112,
            32,  34,  44,  58, 102, 174, 160, 124,
            36,  44,  74, 116, 136, 218, 206, 154,
            48,  70, 110, 128, 162, 208, 226, 184,
            98, 128, 156, 174, 206, 242, 240, 202,
           144, 184, 190, 196, 224, 200, 206, 198
         },
         // STEEP
         new int[]
         {
            32,  25,  24,  36,  55,  92, 117, 140,
            25,  25,  28,  43,  59, 128, 138, 126,
            28,  25,  32,  55,  92, 131, 158, 128,
            32,  39,  50,  66, 117, 200, 184, 142,
            41,  50,  85, 133, 156, 250, 236, 177,
            55,  80, 126, 147, 186, 239, 259, 211,
           112, 147, 179, 200, 236, 278, 276, 232,
           165, 211, 218, 225, 257, 230, 236, 227
         }
      },

      // CHROMA U (Cb)
      new int[][]
      {
         // FLAT
         new int[]
         {
             32,  36,  48,  79, 160, 160, 160, 160,
             36,  40,  52, 105, 160, 160, 160, 160,
             48,  52,  90, 160, 160, 160, 160, 160,
             79, 110, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160
         },
         // BALANCED
         new int[]
         {
            32,  37,  50,  97, 200, 200, 200, 200,
            37,  43,  54, 138, 200, 200, 200, 200,
            50,  54, 117, 200, 200, 200, 200, 200,
            97, 138, 200, 200, 200, 200, 200, 200,
           200, 200, 200, 200, 200, 200, 200, 200,
           200, 200, 200, 200, 200, 200, 200, 200,
           200, 200, 200, 200, 200, 200, 200, 200,
           200, 200, 200, 200, 200, 200, 200, 200
         },
         // STEEP
         new int[]
         {
            36,  43,  62, 119, 230, 230, 230, 230,
            43,  53,  67, 166, 230, 230, 230, 230,
            62,  67, 142, 230, 230, 230, 230, 230,
           108, 151, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230
         }
      },

      // CHROMA V (Cr)
      new int[][]
      {
         // FLAT
         new int[]
         {
             34,  35,  44,  75, 160, 160, 160, 160,
             35,  38,  48,  99, 160, 160, 160, 160,
             44,  48,  84, 160, 160, 160, 160, 160,
             75, 105, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160,
            160, 160, 160, 160, 160, 160, 160, 160
         },
         // BALANCED
         new int[]
         {
             34,  36,  48,  94, 200, 200, 200, 200,
             36,  42,  52, 132, 200, 200, 200, 200,
             48,  52, 112, 200, 200, 200, 200, 200,
             94, 132, 200, 200, 200, 200, 200, 200,
            200, 200, 200, 200, 200, 200, 200, 200,
            200, 200, 200, 200, 200, 200, 200, 200,
            200, 200, 200, 200, 200, 200, 200, 200,
            200, 200, 200, 200, 200, 200, 200, 200
         },
         // STEEP
         new int[]
         {
            36,  39,  56, 108, 230, 230, 230, 230,
            39,  48,  60, 151, 230, 230, 230, 230,
            56,  60, 129, 230, 230, 230, 230, 230,
           108, 151, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230,
           230, 230, 230, 230, 230, 230, 230, 230
         }
      }
   };


   // first index: channel type (y, u, v)
   // second index: quantization strength (flat, balanced, steep)
   public int[][][] getTables(int dim)
   {
      return (dim == 4) ? TABLES_4x4 : ((dim == 8) ? TABLES_8x8 : null);
   }


   public int getQuantizer(int qidx)
   {
      if (qidx < 0)
         return QSCALE[0];

      if (qidx >= QSCALE.length)
         return QSCALE[QSCALE.length-1];

      return QSCALE[qidx];
   }
}

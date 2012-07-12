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

public class Tables
{
   public static final int FLAT = 0;
   public static final int BALANCED = 1;
   public static final int STEEP = 2;

   public static final Tables SINGLETON = new Tables();

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
      32, 48, 32, 48,
      48, 72, 48, 72,
      32, 48, 32, 48,
      48, 72, 48, 72
   };

   private static final int[][][] TABLES_4x4 =
   {
      // LUMA
      new int[][] { FLAT_4x4, BALANCED_4x4, STEEP_4x4 },
      // CHROMA U
      new int[][] { FLAT_4x4, BALANCED_4x4, STEEP_4x4 },
      // CHROMA V
      new int[][] { FLAT_4x4, BALANCED_4x4, STEEP_4x4 }
   };


   private static final int[][][] TABLES_8x8 =
   {
      // LUMA
      new int[][]
      {
         // FLAT
         new int[]
         {
             32,  24,  26,  32,  38,  46,  70, 116,
             24,  25,  26,  32,  40,  48,  73, 121,
             26,  26,  30,  35,  44,  54,  83, 136,
             32,  32,  35,  41,  46,  65, 100, 164,
             38,  33,  38,  52,  60,  86, 131, 216,
             46,  48,  54,  65,  86, 121, 185, 307,
             70,  73,  83, 100, 131, 185, 283, 468,
            116, 121, 136, 164, 216, 307, 468, 774
         },
         // BALANCED
         new int[]
         {
             32,  22,  26,  32,  40,  58,  88, 146,
             22,  24,  26,  32,  42,  60,  92, 152,
             26,  26,  30,  36,  48,  68, 104, 170,
             32,  32,  36,  44,  58,  82, 126, 206,
             40,  42,  48,  58,  76, 108, 164, 270,
             58,  60,  68,  82, 108, 152, 232, 384,
             88,  92, 104, 126, 164, 232, 354, 586,
            146, 152, 170, 206, 270, 384, 586, 968
         },
         // STEEP
         new int[]
         {
             32,  20,  24,  36,  46,  66, 101,  167,
             20,  21,  24,  36,  48,  69, 105,  174,
             24,  24,  29,  41,  55,  78, 119,  195,
             36,  36,  41,  50,  66,  94, 144,  236,
             46,  48,  55,  66,  87, 124, 188,  310,
             66,  69,  78,  94, 124, 174, 266,  441,
            101, 105, 119, 144, 188, 266, 407,  673,
            167, 174, 195, 236, 310, 441, 673, 1113
         }
      },

      // CHROMA U (Cb)
      new int[][]
      {
         // FLAT
         new int[]
         {
             36,  30,  59,  90, 161, 198, 284, 397,
             30,  28,  55,  74, 124, 150, 212, 292,
             59,  55,  98, 107, 161, 181, 243, 327,
            107,  88, 128, 204, 235, 241, 305, 392,
            161, 124, 161, 235, 363, 335, 397, 486,
            235, 179, 216, 286, 398, 478, 538, 631,
            337, 252, 289, 363, 472, 538, 739, 788,
            472, 347, 388, 465, 577, 631, 788, 997
         },
         // BALANCED
         new int[]
         {
             38,  46,  88, 134, 202, 294,  422,  590,
             46,  42,  82, 110, 156, 224,  316,  434,
             88,  82, 146, 160, 202, 270,  362,  486,
            134, 110, 160, 256, 294, 358,  454,  582,
            202, 156, 202, 294, 454, 498,  590,  722,
            294, 224, 270, 358, 498, 710,  800,  938,
            422, 316, 362, 454, 590, 800, 1098, 1170,
            590, 434, 486, 582, 722, 938, 1170, 1480
         },
         // STEEP
         new int[]
         {
             45,  24,  29, 154, 232,  338,  485,  678,
             24,  37,  29, 126, 179,  257,  363,  499,
             29,  29,  35, 184, 232,  310,  416,  558,
            154, 126, 184, 294, 338,  411,  522,  669,
            232, 179, 232, 338, 522,  572,  678,  830,
            338, 257, 310, 411, 572,  816,  920, 1078,
            485, 363, 416, 522, 678,  920, 1262, 1345,
            678, 499, 558, 669, 830, 1078, 1345, 1702
         }
      },
      
      // CHROMA V (Cr)
      new int[][]
      {
         // FLAT
         new int[]
         {
             36,  32,  84, 129, 174, 294, 424, 466,
             32,  31,  78, 107, 136, 223, 316, 438,
             84,  78, 146, 159, 177, 270, 365, 466,
            113,  94, 139, 227, 256, 358, 455, 466,
            174, 136, 177, 256, 401, 466, 466, 466,
            257, 195, 236, 313, 408, 466, 466, 466,
            371, 276, 320, 398, 408, 466, 466, 466,
            408, 384, 408, 408, 408, 466, 466, 466
         },
         // BALANCED
         new int[]
         {
             28,  36,  92, 142, 218, 322, 464, 510,
             36,  34,  86, 118, 170, 244, 346, 480,
             92,  86, 160, 174, 222, 296, 400, 510,
            142, 118, 174, 284, 320, 392, 498, 510,
            218, 170, 222, 320, 502, 510, 510, 510,
            322, 244, 296, 392, 510, 510, 510, 510,
            464, 346, 400, 498, 510, 510, 510, 510,
            510, 480, 510, 510, 510, 510, 510, 510
         },
         // STEEP
         new int[]
         {
             24,  17,  21, 163, 250, 370, 533, 586,
             17,  30,  21, 135, 195, 280, 397, 552,
             21,  21,  26, 200, 255, 340, 460, 586,
            163, 135, 200, 326, 368, 450, 572, 586,
            250, 195, 255, 368, 577, 586, 586, 586,
            370, 280, 340, 450, 586, 586, 586, 586,
            533, 397, 460, 572, 586, 586, 586, 586,
            586, 552, 586, 586, 586, 586, 586, 586
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

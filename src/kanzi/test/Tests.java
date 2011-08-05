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

package kanzi.test;


public class Tests
{
   public static void main(String[] args)
   {
       long before = System.nanoTime();
       TestMTFT.main(args);
       System.out.println("======================");
       TestRLT.main(args);
       System.out.println("======================");
       TestZLT.main(args);
//       System.out.println("======================");
///       TestDCT.main(args); // needs Lena picture
//       System.out.println("======================");
//       TestDWT.main(args);
       System.out.println("======================");
       TestBWT.main(args);
       System.out.println("======================");
       TestQuickSort.main(args);
       System.out.println("======================");
       TestRadixSort.main(args);
       System.out.println("======================");
       TestInsertionSort.main(args);
       System.out.println("======================");
       TestBucketSort.main(args);
       System.out.println("======================");
       TestHeapSort.main(args);
       System.out.println("======================");
       TestRangeCoder.main(args);
//       System.out.println("======================");
///       TestHuffmanCoder.main(args); // needs document1.txt
       System.out.println("======================");
       TestExpGolombCoder.main(args);
       long after = System.nanoTime();
       System.out.println("\n\n -------------------------------------- \n");
       System.out.println("Elapsed [ms]: " + (after - before) / 1000000);
    }
}

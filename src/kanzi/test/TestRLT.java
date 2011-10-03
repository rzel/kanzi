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

import kanzi.IndexedByteArray;
import kanzi.function.RLT;
import java.util.Arrays;
import java.util.Random;


public class TestRLT
{
    public static void main(String[] args)
    {
        System.out.println("TestRLT");
        byte[] input;
        byte[] output;
        byte[] reverse;
        Random rnd = new Random();

        // Test behavior
        {
           System.out.println("Correctness test");
           for (int ii=0; ii<20; ii++)
           {
              System.out.println("\nTest "+ii);
              int[] arr;

              if (ii == 2)
              {
                 arr = new int[] {
                    0, 1, 2, 2, 2, 2, 7, 9,  9, 16, 16, 16, 1, 3,
                   3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3
                 };
              }
              else if (ii == 1)
              {
                 arr = new int[] { 0, 1, 3, 4, 5, 6, 7, 7 };
              }
              else if (ii == 0)
              {
                 arr = new int[] { 0, 0, 1, 1, 2, 2, 3, 3 };
              }
              else
              {
                 arr = new int[1024];
                 int idx = 0;

                 while (idx < arr.length)
                 {
                    int len = rnd.nextInt(270);

                    if (len % 3 == 0)
                      len = 1;

                     int val = rnd.nextInt(256);
                     int end = (idx+len) < arr.length ? idx+len : arr.length;

                     for (int j=idx; j<end; j++)
                        arr[j] = val;

                    idx += len;
                    System.out.print(val+" ("+len+") ");
                 }
              }

               int size = arr.length;
               input = new byte[size];
               output = new byte[size];
               reverse = new byte[size];
               IndexedByteArray iba1 = new IndexedByteArray(input, 0);
               IndexedByteArray iba2 = new IndexedByteArray(output, 0);
               IndexedByteArray iba3 = new IndexedByteArray(reverse, 0);
               Arrays.fill(output, (byte) 0xAA);

               for (int i = 0; i < arr.length; i++)
               {
                  input[i] = (byte) (arr[i] & 255);

                  for (int j=arr.length; j<size; j++)
                      input[j] = (byte) (0);
               }

               RLT rlt = new RLT();

               System.out.println("\nOriginal: ");

               for (int i = 0; i < input.length; i++)
               {
                  System.out.print((input[i] & 255) + " ");
               }

               System.out.println("\nCoded: ");

               if (rlt.forward(iba1, iba2) == false)
               {
                  System.out.println("Encoding error");
                  continue;
               }

               if (iba1.index != input.length)
               {
                  System.out.println("No compression (ratio > 1.0), skip reverse");
                  continue;
               }

               //java.util.Arrays.fill(input, (byte) 0);

               for (int i = 0; i < iba2.index; i++)
               {
                  System.out.print((output[i] & 255) + " "); //+"("+Integer.toBinaryString(output[i] & 255)+") ");
               }

               rlt = new RLT(); // Required to reset internal attributes
               iba1.index = 0;
               iba2.index = 0;
               iba3.index = 0;
               rlt.inverse(iba2, iba3);
               System.out.println("\nDecoded: ");

               for (int i = 0; i < reverse.length; i++)
               {
                  System.out.print((reverse[i] & 255) + " ");
               }

               System.out.println();

               for (int i = 0; i < input.length; i++)
               {
                  if (input[i] != reverse[i])
                  {
                     System.out.println("Different (index "+i+": "+input[i]+" - "+reverse[i]+")");
                     System.exit(1);
                  }
               }

               System.out.println("Identical");
               System.out.println();
            }
      }

      // Test speed
      {
         System.out.println("\n\nSpeed test\n");
         input = new byte[30000];
         output = new byte[input.length];
         IndexedByteArray iba1 = new IndexedByteArray(input, 0);
         IndexedByteArray iba2 = new IndexedByteArray(output, 0);

         for (int i = 0; i < input.length; i++)
         {
            input[i] = (byte) (i & 255);
         }

         RLT rlt = new RLT();
         long before, after;
         long delta1 = 0;
         long delta2 = 0;
         int iter = 100000;

         before = System.nanoTime();

         for (int ii = 0; ii < iter; ii++)
         {
            rlt = new RLT(); // Required to reset internal attributes
            iba1.index = 0;
            iba2.index = 0;
            rlt.forward(iba1, iba2);
         }

         after = System.nanoTime();
         delta1 += (after - before);
         before = System.nanoTime();

         for (int ii = 0; ii < iter; ii++)
         {
            rlt = new RLT(); // Required to reset internal attributes
            iba1.index = 0;
            iba2.index = 0;
            rlt.inverse(iba2, iba1);
         }

         after = System.nanoTime();
         delta2 += (after - before);
         System.out.println("Iterations: "+iter);
         System.out.println("RLT encoding [ms]: " + delta1 / 1000000);
         System.out.println("RLT decoding [ms]: " + delta2 / 1000000);
      }
   }
}

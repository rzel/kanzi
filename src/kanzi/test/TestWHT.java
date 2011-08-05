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

import kanzi.transform.WHT8;

public class TestWHT
{

  public static void main(String[] args)
  {
       Runnable r1 = new Runnable()
        {
            public void run()
            {
                for (int times=0; times<20; times++)
                {
                    int[][] data = new int[50000][];
                    WHT8 wht = new WHT8();

                    for (int i=0; i<50000; i++)
                    {
                        data[i] = new int[64];

                        for (int j=0; j<64; j++)
                            data[i][j] = i*50000+j;
                    }

                    long before = System.nanoTime();

                    for (int i=0; i<500000; i++)
                    {
                       wht.forward(data[i%2], 0);
                       wht.inverse(data[i%2], 0);
                    }

                    long after = System.nanoTime();

                    System.out.print("Elapsed [ms]: ");
                    System.out.println((after-before)/1000000);
                }
            }
        };

        System.out.println("\nWHT4 speed");

        // Speed test
        r1.run();


        Runnable r2 = new Runnable()
        {
            int[] block = new int[] { 18960, 15161, 17066, 17653, -1417, 270, -1796, -1706,
            15408, 15323, 21256, 17566, 68, -1087, 674, -3323, 15432, 12951, 16109, 15096, 1306, 
            -2830, 3102, 5201, 14475, 10968, 15136, 17099, 2235, -2246, 446, 2436, 203, 
            -481, 0, -1458, -120, -433, -981, 924, -79, 1242, -2125, 784, -981, 993, 2051, 
            250, -110, -112, 1104, 471, 798, 1051, -947, -337, 1091, -1212, 2004, 1180, -256,
            911, 2107, -1983 };
            
            public void run()
            {                
               int[] data = new int[64];
              WHT8 wht = new WHT8();

              System.out.println("Input");
              for (int i = 0; i < 64; i++)
              {
                 data[i] = i; //block[i] >> 7;
                 System.out.print(data[i]);
                 System.out.print(" ");
              }

              wht.forward(data, 0);
              System.out.println();
              System.out.println("Output");

              for (int i = 0; i < 64; i++)
              {
                 System.out.print(data[i]);
                 System.out.print(" ");
              }

              wht.inverse(data, 0);
              System.out.println();
              System.out.println("Result");

              for (int i = 0; i < 64; i++)
              {
                 System.out.print(data[i]);
                 System.out.print(" ");
              }

               System.out.println("");
           }

        };
        
        System.out.println("\nWHT8 validity");
        
        // Validity test
        r2.run();
    }
}
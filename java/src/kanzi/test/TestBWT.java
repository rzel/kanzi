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

package kanzi.test;

import java.util.Random;
import kanzi.transform.BWT;


public class TestBWT
{
    public static void main(String[] args)
    {
        System.out.println("TestBWT");
        System.out.println("\nCorrectness test");

        // Test behavior
        for (int ii=1; ii<=20; ii++)
        {
            System.out.println("\nTest "+ii);
            int start = 0;
            int size;
            byte[] buf1;
            Random rnd = new Random();

            if (ii == 1)
            {
               size = 0;
               buf1 = "mississippi".getBytes();
            }
            else
            {
               size = 128;
               buf1 = new byte[size];

               for (int i=0; i<buf1.length; i++)
               {
                   buf1[i] = (byte) (65 + rnd.nextInt(32));
               }

               buf1[buf1.length-1] = (byte) 0;
            }

            BWT bwt = new BWT(size);
            String str1 = new String(buf1);
            System.out.println("Input:   "+str1);
            byte[] buf2 = bwt.forward(buf1, start);
            int primaryIndex = bwt.getPrimaryIndex();
            String str2 = new String(buf2);
            System.out.print("Encoded: "+str2);
            System.out.println("  (Primary index="+bwt.getPrimaryIndex()+")");
            bwt.setPrimaryIndex(primaryIndex);
            byte[] buf3 = bwt.inverse(buf2, start);
            String str3 = new String(buf3);
            System.out.println("Output:  "+str3);

            if (str1.equals(str3) == true)
               System.out.println("Identical");
            else
            {
               System.out.println("Different");
               System.exit(1);
            }
        }


        // Test Speed
        {
            System.out.println("\nSpeed test");
            int iter = 2000;
            long delta1 = 0;
            long delta2 = 0;

            for (int jj = 0; jj < 20; jj++)
            {
                int size = 8192;
                byte[] buf1 = new byte[size];
                BWT bwt = new BWT(size);
                java.util.Random random = new java.util.Random();

                for (int i = 0; i < size; i++)
                    buf1[i] = (byte) (random.nextInt(64) + 1);

                buf1[size-1] = 0;
                long before, after;

                for (int ii = 0; ii < iter; ii++)
                {
                    before = System.nanoTime();
                    byte[] buf2 = bwt.forward(buf1, 0);
                    after = System.nanoTime();
                    delta1 += (after - before);
                    before = System.nanoTime();
                    bwt.inverse(buf2, 0);
                    after = System.nanoTime();
                    delta2 += (after - before);
                }
            }

            System.out.println("Iterations: "+iter);
            System.out.println("Forward transform [ms]: " + delta1 / 1000000);
            System.out.println("Inverse transform [ms]: " + delta2 / 1000000);
        }
    }
}

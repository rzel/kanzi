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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import kanzi.bitstream.DebugOutputBitStream;
import kanzi.bitstream.DefaultInputBitStream;
import kanzi.bitstream.DefaultOutputBitStream;
import kanzi.prediction.ResidueBlockDecoder;
import kanzi.prediction.ResidueBlockEncoder;


public class TestResidueCoder
{


    public static void main(String[] args)
    {
       System.out.println("TestResidueCoder");

        // Test behavior
        try
        {
            System.out.println("\nCorrectness test");

            Random rnd = new Random();

            for (int ii=0; ii<20; ii++)
            {
               byte[] data1 = new byte[128];
               byte[] data2 = new byte[128];
               System.out.println("Source");
               
               for (int i=0; i<64; i++)
               {
                  int v = rnd.nextInt(30) - 15;

                  if ((v%3) == 0)
                     v = 0;
                  else if((v % 4) == 0)
                     v = 0;
                  else if((v % 9) == 0)
                     v = rnd.nextInt(300) - 150;

                  data1[i<<1] = (byte) (v >> 8);
                  data1[(i<<1)+1] = (byte) (v & 0xFF);
                  System.out.print(data1[i<<1]+", "+data1[(i<<1)+1]+", ");
               }

               System.out.println();
               ByteArrayOutputStream os = new ByteArrayOutputStream();
//               OutputBitStream obs = new DefaultOutputBitStream(os, 8192);
               DebugOutputBitStream obs = new DebugOutputBitStream(new DefaultOutputBitStream(os, 8192), System.out);
               obs.setMark(true);
               ResidueBlockEncoder rbe = new ResidueBlockEncoder(obs, 8);
               rbe.encode(data1, 0, 128);
               obs.close();
               InputStream is = new ByteArrayInputStream(os.toByteArray());
               DefaultInputBitStream ibs = new DefaultInputBitStream(is, 8192);
               ResidueBlockDecoder rbd = new ResidueBlockDecoder(ibs, 8);
               rbd.decode(data2, 0, 128);
               System.out.println("\nDecoded");

               for (int i=0; i<64; i++)
               {
                  System.out.print(data2[i<<1]+", ");

                  if (data1[i<<1] != data2[i<<1])
                  {
                     System.err.println("Error at index "+(i<<1)+": "+data2[i<<1]+"<->"+data1[i<<1]);
                     System.exit(1);
                  }

                  System.out.print(data2[(i<<1)+1]+", ");

                  if (data1[(i<<1)+1] != data2[(i<<1)+1])
                  {
                     System.err.println("Error at index "+((i<<1)+1)+": "+data2[(i<<1)+1]+"<->"+data1[(i<<1)+1]);
                     System.exit(1);
                  }
               }

               System.out.println();
               System.out.println();
            }
        }
        catch (Exception e)
        {
           e.printStackTrace();
        }

    }
}

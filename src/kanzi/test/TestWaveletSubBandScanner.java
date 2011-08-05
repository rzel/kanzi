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

import kanzi.function.wavelet.WaveletBandIterator;


public class TestWaveletSubBandScanner
{
    
    public static void main(String[] args)
    {
        int size = 512;
//        int[] buffer = new int[size*size];
        int[] output = new int[size*size];
        
//        for (int i=0; i<buffer.length; i++)
//            buffer[i] = i;
        
        System.out.println("\nOne shot Scan");
        WaveletBandIterator scanner = new WaveletBandIterator(8, size,
                WaveletBandIterator.ALL_BANDS);
        int maxLoop = 1;
        int n = 0;
        long before = System.nanoTime();
        
        for (int ii=0; ii<maxLoop; ii++)
          n = scanner.getIndexes(output);       
        
        long after = System.nanoTime();
        
        if (maxLoop > 1)
            System.out.println("Elapsed [ms]: "+(after-before)/1000000);
        
        System.out.println(n+" coefficients");
        
        for (int i=0; i<n; i+=100)
        {
            for (int j=i; j<i+100; j++)
                System.out.print(output[j]+" ");
            
            System.out.println();
        }
        
        System.out.println("\nPartial Scan");
        output = new int[100];
        int count;
        n = 0;
        
        while (scanner.hasNext())
        {
            count = scanner.getNextIndexes(output, output.length);
            System.out.println(count+" coefficients");
            n++;
            
            for (int i=0; i<count; i++)
                System.out.print(output[i]+" ");
            
            System.out.println();
        }
        
    }
}

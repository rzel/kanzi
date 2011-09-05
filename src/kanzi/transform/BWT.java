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

package kanzi.transform;

// The Burrows-Wheeler Transform is a reversible transform based on

import kanzi.ByteTransform;

// permutation of the data in the original message to reduce the entropy.

// The initial text can be found here:
// Burrows M and Wheeler D, [A block sorting lossless data compression algorithm]
// Technical Report 124, Digital Equipment Corporation, 1994

// See also Peter Fenwick, [Block sorting text compression - final report]
// Technical Report 130, 1996

// This implementation replaces the 'slow' sorting of permutation strings
// with the construction of a suffix array (faster but more complex).
// The suffix array contains the indexes of the sorted suffixes.
// This implementation (a.k.a KS) is based on the Skew algorithm (KarkKainen & Sanders)
// The method uses integer sorting to create the suffix array in linear time.
//
// It is a port from the C code in [Simple Linear Work Suffix Array Construction]
// by Juha KarkKainen & Peter Sanders.
//
// e.g. mississippi\0
// Suffixes:
// mississippi\0 0
//  ississippi\0 1
//   ssissippi\0 2
//    sissippi\0 3
//     issippi\0 4
//      ssippi\0 5
//       sippi\0 6
//        ippi\0 7
//         ppi\0 8
//          pi\0 9
//           i\0 10
// Suffix array        10 7 4 1 0 9 8 6 3 5 2 => ipssm\0pissii
// The suffix array and permutation vector are equal when the input is 0 terminated
// In this example, for a non \0 terminated string the permutation vector is pssmipissii.
// The insertion of a guard is done internally and is entirely transparent.
//
// High level description of the algorithm:
// - Recursively sort suffixes starting at non-multiple-of-3 positions
// - Sort the remaining suffixes using the result of the previous sort
// - Merge the two sorted sets


public class BWT implements ByteTransform
{
    private int size;
    private final int[] buckets;
    private int[] sa; //suffix array
    private byte[] buffer;
    private int[] array;
    private int primaryIndex;


    public BWT()
    {
        this(0);
    }


    // Static allocation of memory
    public BWT(int size)
    {
        if (size < 0)
            throw new IllegalArgumentException("Invalid size parameter (must be at least 0)");

        this.size = size;
        this.buckets = new int[256];
        this.buffer = new byte[size];
        this.sa = new int[size];
        this.array = new int[(size == 0) ? 0 : size+3];
    }


    public int getPrimaryIndex()
    {
       return this.primaryIndex;
    }


    // Not thread safe
    public boolean setPrimaryIndex(int primaryIndex)
    {
       if (primaryIndex < 0)
          return false;

       this.primaryIndex = primaryIndex;
       return true;
    }


    public int size()
    {
       return this.size;
    }


    public boolean setSize(int size)
    {
       if (size < 0)
           return false;

       this.size = size;
       return true;
    }


    // Not thread safe
    @Override
    public byte[] forward(byte[] input, int blkptr)
    {
        final int len = (this.size != 0) ? this.size : input.length - blkptr;

        // Dynamic memory allocation
        if (this.sa.length < len)
           this.sa = new int[len];

        if (this.buffer.length < len)
           this.buffer = new byte[len];

        // Create a list that contains the start index of each permutation
        // of the string. EG:
        // index 1: FREDERIC, index 2: CFREDERI, index 3: ICFREDER, etc...
        final int len8 = len & 0xFFFFFFF8;

        for (int i=0; i<len8; )
        {
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
            this.sa[i] = i++;
        }

        for (int i=len8; i<len; i++)
            this.sa[i] = i;

        // Sort the permutations, get the permutation vector
        int[] suffixArray = this.sort(input, this.sa, blkptr, len);
        int i = 0;

        // The permutation vector can now be used to generate the output
        for ( ; i<len; i++)
        {
            // Get the index of the last byte of each permutation
            int val = suffixArray[i];

            if (val == 0)
            {
                // Found the inserted 0, set primary index, do not copy
                this.setPrimaryIndex(i);
                break;
            }

            this.buffer[i+1] = input[blkptr+val-1];
        }

        for (i++ ; i<len; i++)
        {
            // Get the index of the last byte of each permutation
            this.buffer[i] = input[blkptr+suffixArray[i]-1];
        }

        this.buffer[0] = input[blkptr+len-1];
        System.arraycopy(this.buffer, 0, input, blkptr, len);
        return input;
    }


    // Not thread safe
    @Override
    public byte[] inverse(byte[] input, int blkptr)
    {
       int len = (this.size != 0) ? this.size : input.length - blkptr;

       // Dynamic memory allocation
       if (this.array.length < len)
          this.array = new int[len];

       if (this.buffer.length < len)
          this.buffer = new byte[len];

       for (int i=0; i<256; i+=8)
       {
          this.buckets[i]   = 0;
          this.buckets[i+1] = 0;
          this.buckets[i+2] = 0;
          this.buckets[i+3] = 0;
          this.buckets[i+4] = 0;
          this.buckets[i+5] = 0;
          this.buckets[i+6] = 0;
          this.buckets[i+7] = 0;
       }

       for (int i=0; i<len; i++)
       {
          this.array[i] = this.buckets[input[blkptr+i] & 0xFF]++;
       }

       for (int i=0, sum=0; i<256; i++)
       {
          int val = this.buckets[i];
          this.buckets[i] = sum;
          sum += val;
       }

       int pidx = this.getPrimaryIndex();

       for (int i=len-1, val=0; i >= 0; i--)
       {
          int val2 = input[blkptr+val] & 0xFF;
          this.buffer[i] = (byte) val2;
          val = this.array[val] + this.buckets[val2];

          if (val <= pidx)
             val++;
       }

       System.arraycopy(this.buffer, 0, input, blkptr, len);
       return input;
    }


    // Compute the suffix array in linear time using KarkKainen & Sanders method
    // In order to use a suffix array in the BWT, the last byte of the input block
    // must be 0. The suffix array and permutation vector are equal only when
    // the input is 0 terminated. Also, no 0 is allowed in the rest of the data.
    // In other word, we add a guard at the end which has the lowest value.
    protected int[] sort(byte[] block, int[] sa, int blkptr, int len)
    {
        if (this.array.length < len + 3)
           this.array = new int[len+3];

        int len4 = len & 0xFFFFFFFC;

        // Copy and extend the alphabet from [0..255] to [1..256]
        for (int i=0; i<len4; )
        {
            this.array[i] = 1 + (block[blkptr+i] & 0xFF);
            i++;
            this.array[i] = 1 + (block[blkptr+i] & 0xFF);
            i++;
            this.array[i] = 1 + (block[blkptr+i] & 0xFF);
            i++;
            this.array[i] = 1 + (block[blkptr+i] & 0xFF);
            i++;
        }

        for (int i=len4; i<len; i++)
           this.array[i] = 1 + (block[blkptr+i] & 0xFF);

        // Add 0 at the end of the data
        this.array[len]   = 0;
        this.array[len+1] = 0;
        this.array[len+2] = 0;
        this.suffixArray(this.array, sa, len, 256);
        return sa;
    }


    // Stably sort input[0..n-1] to output[0..n-1]
    // Critical path for speed
    private void radixPass(int[] input, int[] output, int[] sa, int[] freq,
            int idx, int n)
    {
         int n8 = n & 0xFFFFFFF8;

         for (int i=0; i<n8; i+=8)
         {
            freq[sa[idx+input[i]]]++;
            freq[sa[idx+input[i+1]]]++;
            freq[sa[idx+input[i+2]]]++;
            freq[sa[idx+input[i+3]]]++;
            freq[sa[idx+input[i+4]]]++;
            freq[sa[idx+input[i+5]]]++;
            freq[sa[idx+input[i+6]]]++;
            freq[sa[idx+input[i+7]]]++;
         }

         for (int i=n8; i<n; i++)
            freq[sa[idx+input[i]]]++;

         for (int i=0, sum=0; i<freq.length; i++)
         {
            final int temp = freq[i];
            freq[i] = sum;
            sum += temp;
         }

         for (int i=0; i<n8; i+=8)
         {
            final int val0 = input[i];
            output[freq[sa[idx+val0]]++] = val0;
            final int val1 = input[i+1];
            output[freq[sa[idx+val1]]++] = val1;
            final int val2 = input[i+2];
            output[freq[sa[idx+val2]]++] = val2;
            final int val3 = input[i+3];
            output[freq[sa[idx+val3]]++] = val3;
            final int val4 = input[i+4];
            output[freq[sa[idx+val4]]++] = val4;
            final int val5 = input[i+5];
            output[freq[sa[idx+val5]]++] = val5;
            final int val6 = input[i+6];
            output[freq[sa[idx+val6]]++] = val6;
            final int val7 = input[i+7];
            output[freq[sa[idx+val7]]++] = val7;
         }

         for (int i=n8; i<n; i++)
         {
            final int val = input[i];
            output[freq[sa[idx+val]]++] = val;
         }
    }


    // Requires s[n]=s[n+1]=s[n+2]=0, n>=2
    // Requires values in [0..k] range
    private void suffixArray(int[] s, int[] sa, int n, int k)
    {
        int n0 = (n + 2) / 3;
        int n1 = (n + 1) / 3;
        int n2 = n / 3;
        int n02 = n0 + n2;

        // Array that contains suffixes for non-multiple-of-3 positions
        int[] s12  = new int[n02+3];
        int[] sa12 = new int[n02+3];
        int end = n + (n0 - n1);
        int end3 = 3 * (end / 3);
        int ii = 0;

        // Generate positions of mod 1 and mod 2 suffixes
        // '+(n0-n1)' adds a dummy mod 1 suffix if n % 3 == 1
        for (int i=0; i<end3; i+=3)
        {
            s12[ii++] = i + 1;
            s12[ii++] = i + 2;
        }

        for (int i=end3+1; i<end; i++)
            s12[ii++] = i;

        // Radix sort the mod 1 and mod 2 triples
        int[] freq = new int[k+1];
        this.radixPass(s12 , sa12, s, freq, 2, n02);
        int freq4 = freq.length & 0xFFFFFFFC;

        for (int i=0; i<freq4; i+=4)
        {
            freq[i]   = 0;
            freq[i+1] = 0;
            freq[i+2] = 0;
            freq[i+3] = 0;
        }

        for (int i=freq4; i<freq.length; i++)
            freq[i] = 0;

        this.radixPass(sa12, s12 , s, freq, 1, n02);

        for (int i=0; i<freq4; i+=4)
        {
            freq[i]   = 0;
            freq[i+1] = 0;
            freq[i+2] = 0;
            freq[i+3] = 0;
        }

        for (int i=freq4; i<freq.length; i++)
            freq[i] = 0;

        this.radixPass(s12 , sa12, s, freq, 0, n02);

        // Find lexicographic names of triples
        int name = 0;
        int c0 = -1;
        int c1 = -1;
        int c2 = -1;

        for (int i=0; i<n02; i++)
        {
            int idx = sa12[i];

            if (s[idx] != c0)
            {
                name++;
                c0 = s[idx];
                c1 = s[idx+1];
                c2 = s[idx+2];
            }
            else if (s[idx+1] != c1)
            {
                name++;
                c1 = s[idx+1];
                c2 = s[idx+2];
            }
            else if (s[idx+2] != c2)
            {
                name++;
                c2 = s[idx+2];
            }

            // Just for fun: nice trick !
            int div3 = (int) (((long) idx * 0xAAAAAAABL) >> 33);

            if (idx == (3 * div3) + 1)
                s12[div3] = name;
            else
                s12[div3+n0] = name;
        }

        // Recurse if names are not yet unique
        if (name < n02)
        {
            this.suffixArray(s12, sa12, n02, name);

            // Store unique names in s12 using the suffix array
            for (int i=0; i<n02; i++)
                s12[sa12[i]] = i + 1;
        }
        else
        {
            // Generate the suffix array of s12 directly
            for (int i=0; i<n02;  i++)
                sa12[s12[i]-1] = i;
        }

        // Array that contains suffixes for multiple-of-3 positions
        int[] s0  = new int[n0];
        int[] sa0 = new int[n0];

        // Stably sort the mod 0 suffixes from sa12 by their first character
        for (int i=0, j=0; i<n02; i++)
        {
            if (sa12[i] < n0)
                s0[j++] = 3 * sa12[i];
        }

        for (int i=0; i<freq4; i+=4)
        {
            freq[i]   = 0;
            freq[i+1] = 0;
            freq[i+2] = 0;
            freq[i+3] = 0;
        }

        for (int i=freq4; i<freq.length; i++)
            freq[i] = 0;

        this.radixPass(s0, sa0, s, freq, 0, n0);

        // Merge the sorted sa0 suffixes and sorted sa12 suffixes
        for (int p=0, t=n0-n1, l=0; l<n; l++)
        {
            int idx = sa12[t];

            // Position of current offset 1-2 suffix
            int i = (idx < n0) ? (3 * idx) + 1 : (3 * (idx - n0)) + 2;

            // Position of current offset 0 suffix
            int j = sa0[p];
            int si = s[i];
            int sj = s[j];
            boolean leq;

            if (idx < n0)
            {
                if (si == sj)
                   leq = (s12[idx+n0] <= s12[j/3]);
                else
                   leq = (si < sj);
            }
            else
            {
                if (si == sj)
                {
                   if (s[i+1] == s[j+1])
                      leq = (s12[idx-n0+1] <= s12[(j/3)+n0]);
                   else
                      leq = (s[i+1] < s[j+1]);
                }
                else
                   leq = (si < sj);
            }

            if (leq == true)
            {
                sa[l] = i;
                t++;

                if (t != n02)
                   continue;

                // Done: only sa0 suffixes left
                l++;
                System.arraycopy(sa0, p, sa, l, n0-p);
                l += (n0 - p);
                p = n0;

            }
            else
            {
                sa[l] = j;
                p++;

                if (p != n0)
                   continue;

                // Done: only sa12 suffixes left
                for (l++; t<n02; t++, l++)
                {
                   idx = sa12[t];
                   sa[l] = (idx < n0) ? (3 * idx) + 1 : (3 * (idx - n0)) + 2;
                }
            }
        }
    }
}
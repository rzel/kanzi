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

package kanzi.util.sort;

import kanzi.ArrayComparator;
import kanzi.IntSorter;


// HeapSort is a comparison sort with O(n ln n) complexity. Practically, it is
// usually slower than QuickSort.
public final class HeapSort implements IntSorter
{
    private final ArrayComparator cmp;
    private final int size;


    public HeapSort()
    {
        this(0, null);
    }


    public HeapSort(int size)
    {
        this(size, null);
    }


    public HeapSort(int size, ArrayComparator cmp)
    {
        if (size < 0)
            throw new IllegalArgumentException("Invalid size parameter (must be a least 0)");

        this.cmp = cmp;
        this.size = size;
    }


    @Override
    public void sort(int[] array, int blkptr)
    {
        final int sz = (this.size == 0) ? array.length : this.size;

        for (int k=sz>>1; k>0; k--)
        {
            doSort(array, blkptr, k, sz, this.cmp);
        }

        for (int i=sz-1; i>0; i--)
        {
            final int temp = array[blkptr];
            array[blkptr] = array[blkptr+i];
            array[blkptr+i] = temp;
            doSort(array, blkptr, 1, i, this.cmp);
        }
    }


    private static void doSort(int[] array, int blkptr, int idx, int count,
            ArrayComparator cmp)
    {
        int k = idx;
        final int temp = array[blkptr+k-1];
        final int n = count >> 1;

        if (cmp != null)
        {
           while (k <= n)
           {
               int j = k << 1;

               if ((j < count) && (cmp.compare(array[blkptr+j-1], array[blkptr+j]) < 0))
                   j++;

               if (temp >= array[blkptr+j-1])
                   break;

               array[blkptr+k-1] = array[blkptr+j-1];
               k = j;
           }
        }
        else
        {
           while (k <= n)
           {
               int j = k << 1;

               if ((j < count) && (array[blkptr+j-1] < array[blkptr+j]))
                   j++;

               if (temp >= array[blkptr+j-1])
                   break;

               array[blkptr+k-1] = array[blkptr+j-1];
               k = j;
           }
        }

        array[blkptr+k-1] = temp;
    }
}

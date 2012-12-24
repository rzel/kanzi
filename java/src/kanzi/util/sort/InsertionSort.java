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


// Simple sorting algorithm with O(n*n) worst case complexity, O(n+k) on average
// Efficient on small data sets
public class InsertionSort implements IntSorter
{
    private final ArrayComparator cmp;
    private final int size;
    
    
    public InsertionSort()
    {
        this(0, null);
    }
    
    
    public InsertionSort(int size)
    {
        this(size, null);
    }
    
    
    public InsertionSort(int size, ArrayComparator cmp)
    {
        if (size < 0)
            throw new IllegalArgumentException("Invalid size parameter (must be a least 0)");
        
        this.size = size;
        this.cmp = cmp;
    }
    
    
   @Override
    public void sort(int[] input, int blkptr)
    {
        final int sz = (this.size == 0) ? input.length - blkptr : this.size;
        
        if (this.cmp == null)
            sortNoComparator(input, blkptr, blkptr+sz);
        else
            sortWithComparator(input, blkptr, blkptr+sz, this.cmp);
    }
    
    
    private static void sortWithComparator(int[] array, int blkptr, int end, ArrayComparator comp)
    {
        // Shortcut for 2 element-sub-array
        if (end == blkptr + 1)
        {
            if (comp.compare(array[blkptr], array[end]) > 0)
            {
                final int tmp = array[blkptr];
                array[blkptr] = array[end];
                array[end] = tmp;
            }

            return;
        }

        // Shortcut for 3 element-sub-array
        if (end == blkptr + 2)
        {
            final int a1 = array[blkptr];
            final int a2 = array[blkptr+1];
            final int a3 = array[end];

            if (comp.compare(a1, a2) <= 0)
            {
                if (comp.compare(a2, a3) <= 0)
                    return;

                if (comp.compare(a3, a1) <= 0)
                {
                    array[blkptr]   = a3;
                    array[blkptr+1] = a1;
                    array[end]      = a2;
                    return;
                }

                array[blkptr+1] = a3;
                array[end]      = a2;
            }
            else
            {
                if (comp.compare(a1, a3) <= 0)
                {
                    array[blkptr]   = a2;
                    array[blkptr+1] = a1;
                    return;
                }

                if (comp.compare(a3, a2) <= 0)
                {
                    array[blkptr] = a3;
                    array[end]    = a1;
                    return;
                }

                array[blkptr]   = a2;
                array[blkptr+1] = a3;
                array[end]      = a1;
            }

            return;
        }

        for (int i=blkptr; i<end; i++)
        {
            final int val = array[i];
            int j = i;
            
            while ((j > blkptr) && (comp.compare(array[j-1], val) > 0))
            {
                array[j] = array[j-1];
                j--;
            }
            
            array[j] = val;
        }
    }
    
    
    private static void sortNoComparator(int[] array, int blkptr, int end)
    {
        // Shortcut for 2 element-sub-array
        if (end == blkptr + 1)
        {
            if (array[blkptr] > array[end])
            {
                final int tmp = array[blkptr];
                array[blkptr] = array[end];
                array[end] = tmp;
            }

            return;
        }

        // Shortcut for 3 element-sub-array
        if (end == blkptr + 2)
        {
            final int a1 = array[blkptr];
            final int a2 = array[blkptr+1];
            final int a3 = array[end];

            if (a1 <= a2)
            {
                if (a2 <= a3)
                    return;

                if (a3 <= a1)
                {
                    array[blkptr]   = a3;
                    array[blkptr+1] = a1;
                    array[end]      = a2;
                    return;
                }

                array[blkptr+1] = a3;
                array[end]  = a2;
            }
            else
            {
                if (a1 <= a3)
                {
                    array[blkptr]   = a2;
                    array[blkptr+1] = a1;
                    return;
                }

                if (a3 <= a2)
                {
                    array[blkptr]  = a3;
                    array[end]     = a1;
                    return;
                }

                array[blkptr]   = a2;
                array[blkptr+1] = a3;
                array[end]      = a1;
            }
            
            return;
        }

        // Regular case
        for (int i=blkptr; i<end; i++)
        {
            final int val = array[i];
            int j = i;
            
            while ((j > blkptr) && (array[j-1] > val))
            {
                array[j] = array[j-1];
                j--;
            }
            
            array[j] = val;
        }
    }
    
}

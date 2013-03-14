/*
Copyright 2011-2013 Frederic Langlet
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



// One implementation of the most famous sorting algorithm
// There is a lot of litterature about quicksort
// A great reference is http://users.aims.ac.za/~mackay/sorting/sorting.html
// And of course: [Engineering a sort function] by Bentley and McIlroy

public class QuickSort implements IntSorter
{
    private static final int SMALL_ARRAY_THRESHOLD = 32;
    
    private final ArrayComparator cmp;


    public QuickSort()
    {
        this(null);
    }


    public QuickSort(ArrayComparator cmp)
    {
        this.cmp = cmp;
    }


    protected ArrayComparator getComparator()
    {
        return this.cmp;
    }


    @Override
    public boolean sort(int[] input, int blkptr, int len)
    {
        if ((blkptr < 0) || (len <= 0) || (blkptr+len > input.length))
            return false;

        if (len == 1)
           return true;
        
        if (this.cmp != null)
            sortWithComparator(input, blkptr, blkptr+len-1, this.cmp);
        else
            sortNoComparator(input, blkptr, blkptr+len-1);
        
        return true;
    }


    // Ternary partitioning
    private static void sortNoComparator(int[] array, int low, int high)
    {
        if (high <= low + SMALL_ARRAY_THRESHOLD)
        {
            sortSmallArrayNoComparator(array, low, high);
            return;
        }

        // Regular path
        // Choose a pivot: this THE most important step of the algorithm since
        // a bad pivot can really ruin the performance (quadratic). Some research
        // papers show that picking a random index in the [low+1 .. high-1] range
        // is a good choice (on average). Here, a median is used
        final int mid = low + ((high - low) >> 1);
        final int s = (high - low) >> 3;
        
        final int l = (array[low] < array[low+s] ?
            (array[low+s] < array[low+s+s] ? low+s : array[low] < array[low+s+s] ? low+s+s : low) :
            (array[low+s] > array[low+s+s] ? low+s : array[low] > array[low+s+s] ? low+s+s : low));
        final int m = (array[mid-s] < array[mid] ?
            (array[mid] < array[mid+s] ? mid : array[mid-s] < array[mid+s] ? mid+s : mid-s) :
            (array[mid] > array[mid+s] ? mid : array[mid-s] > array[mid+s] ? mid+s : mid-s));
        final int h = (array[high-s-s] < array[high-s] ?
            (array[high-s] < array[high] ? high-s : array[high-s-s] < array[high] ? high : high-s-s) :
            (array[high-s] > array[high] ? high-s : array[high-s-s] > array[high] ? high : high-s-s));
        final int pivIdx = (array[l] < array[m] ?
            (array[m] < array[h] ? m : array[l] < array[h] ? h : l) :
            (array[m] > array[h] ? m : array[l] > array[h] ? h : l));

        final int pivot = array[pivIdx];
        int i = low;
        int mi = low;
        int j = high;
        int mj = high;

        // Use center partition of values equal to pivot
        while (i <= j)
        {
            // Move up
            while ((i <= j) && (array[i] <= pivot))
            {
                if (array[i] == pivot)
                {
                    // Move the pivot value to the low end.
                    // The idea is to accumulate all pivots at the beginning
                    // and move them to the center partition later
                    // Yet, it is not necessary to swap array values (just keep
                    // track of the number of pivots accumulated): skip array[mi] = pivot
                    array[i] = array[mi];
                    mi++;
                }

                i++;
            }

            // Move down
            while ((i <= j) && (pivot <= array[j]))
            {
                if (array[j] == pivot)
                {
                    // Move the pivot value to the high end.
                    // The idea is to accumulate all pivots at the end
                    // and move them to the center partition later
                    // Yet, it is not necessary to swap array values (just keep
                    // track of the number of pivots accumulated): skip array[mj] = pivot
                    array[j] = array[mj];
                    mj--;
                }

                j--;
            }

            if (i <= j)
            {
                final int tmp = array[i];
                array[i] = array[j];
                array[j] = tmp;
                i++;
                j--;
            }
        }

        // Move the pivot values from the ends to the middle
        // The values have not been updated (see optimization above),
        // they are all equal to the pivot
        mi--;
        i--;

        for (; mi>=low; mi--, i--)
        {
            array[mi] = array[i];
            array[i] = pivot;
        }

        mj++;
        j++;

        for (; mj<=high; mj++, j++)
        {
            array[mj] = array[j];
            array[j] = pivot;
        }

        // Sort the low and high sub-arrays
        if (i > low)
           sortNoComparator(array, low, i);

        if (high > j)
           sortNoComparator(array, j, high);
    }


    // Ternary partitioning (the performance may be seriously degraded due to the
    // numerous calls to the array comparator interface).
    private static void sortWithComparator(int[] array, int low, int high, ArrayComparator comp)
    {
        if (high <= low + SMALL_ARRAY_THRESHOLD)
        {
            sortSmallArrayWithComparator(array, low, high, comp);
            return;
        }

        // Regular path
        // Choose a pivot: this THE most important step of the algorithm since
        // a bad pivot can really ruin the performance (quadratic). Some research
        // papers show that picking a random index in the [low+1 .. high-1] range
        // is a good choice (on average). Here, a median is used
        final int mid = low + ((high - low) >> 1);        
        final int s = (high - low) >> 3;
        final int lows = low + s;
        final int highs = high - s;

        final int l = (array[low] < array[low+s] ?
           (array[lows] < array[lows+s] ? lows : array[low] < array[lows+s] ? lows+s : low) :
           (array[lows] > array[lows+s] ? lows : array[low] > array[lows+s] ? lows+s : low));
        final int m = (array[mid-s] < array[mid] ?
           (array[mid] < array[mid+s] ? mid : array[mid-s] < array[mid+s] ? mid+s : mid-s) :
           (array[mid] > array[mid+s] ? mid : array[mid-s] > array[mid+s] ? mid+s : mid-s));
        final int h = (array[highs-s] < array[highs] ?
           (array[highs] < array[high] ? highs : array[highs-s] < array[high] ? high : highs-s) :
           (array[highs] > array[high] ? highs : array[highs-s] > array[high] ? high : highs-s));
        final int pivIdx = (array[l] < array[m] ?
            (array[m] < array[h] ? m : array[l] < array[h] ? h : l) :
            (array[m] > array[h] ? m : array[l] > array[h] ? h : l));

        final int pivot = array[pivIdx];
        int i = low;
        int mi = low;
        int j = high;
        int mj = high;

        // Use center partition of values equal to pivot
        while (i <= j)
        {
            // Move up
            while ((i <= j) && (comp.compare(array[i], pivot) <= 0))
            {
                if (array[i] == pivot)
                {
                    // Move the pivot value to the low end.
                    // The idea is to accumulate all pivots at the beginning
                    // and move them to the center partition later
                    // Yet, it is not necessary to swap array values (just keep
                    // track of the number of pivots accumulated): skip array[mi] = pivot
                    array[i] = array[mi];
                    mi++;
                }

                i++;
            }

            // Move down
            while ((i <= j) && (comp.compare(pivot, array[j]) <= 0))
            {
                if (array[j] == pivot)
                {
                    // Move the pivot value to the high end.
                    // The idea is to accumulate all pivots at the end
                    // and move them to the center partition later
                    // Yet, it is not necessary to swap array values (just keep
                    // track of the number of pivots accumulated): skip array[mj] = pivot
                    array[j] = array[mj];
                    mj--;
                }

                j--;
            }

            if (i <= j)
            {
                int tmp = array[i];
                array[i] = array[j];
                array[j] = tmp;
                i++;
                j--;
            }
        }

        // Move the pivot values from the ends to the middle
        // The values have not been updated (see optimization above),
        // they are all equal to the pivot
        mi--;
        i--;

        for (; mi>=low; mi--, i--)
        {
            array[mi] = array[i];
            array[i] = pivot;
        }

        mj++;
        j++;

        for (; mj<=high; mj++, j++)
        {
            array[mj] = array[j];
            array[j] = pivot;
        }

        // Sort the low and high sub-arrays
        if (i > low)
           sortWithComparator(array, low, i, comp);

        if (high > j)
           sortWithComparator(array, j, high, comp);
    }


    private static void sortSmallArrayWithComparator(int[] array, int low, int high, ArrayComparator comp)
    {
        // Shortcut for 2 element-sub-array
        if (high == low + 1)
        {
            if (comp.compare(array[low], array[high]) > 0)
            {
                final int tmp = array[low];
                array[low] = array[high];
                array[high] = tmp;
            }

            return;
        }

        if (high == low + 2)
        {
           // Shortcut for 3 element-sub-array
           final int a1 = array[low];
           final int a2 = array[low+1];
           final int a3 = array[high];

           if (comp.compare(a1, a2) <= 0)
           {
              if (comp.compare(a2, a3) <= 0)
                return;

              if (comp.compare(a3, a1) <= 0)
              { 
                 array[low]   = a3;
                 array[low+1] = a1;
                 array[high]  = a2;
                 return;
              }

              array[low+1] = a3;
              array[high]  = a2;
          }
          else
          {
             if (comp.compare(a1, a3) <= 0)
             {
                array[low]   = a2;
                array[low+1] = a1;
                return;
             }

             if (comp.compare(a3, a2) <= 0)
             {
                array[low]  = a3;
                array[high] = a1;
                return;
             }

             array[low]   = a2;
             array[low+1] = a3;
             array[high]  = a1;
          }

          return;
       }
 
       // Switch to insertion sort to avoid recursion
       for (int i=low+1; i<=high; i++)  
       {
          final int tmp = array[i];
          int j = i - 1;

          for ( ; ((j >= low) && (comp.compare(array[j], tmp) > 0)); j--) 
             array[j+1] = array[j];

          array[j+1] = tmp;
       }   
    }


    private static void sortSmallArrayNoComparator(int[] array, int low, int high)
    {
       // Shortcut for 2 element-sub-array
       if (high == low + 1) 
       {
          if (array[low] > array[high]) 
          {
             final int tmp = array[low];
             array[low] = array[high];
             array[high] = tmp;
          }

          return;
       }

       if (high == low + 2) 
       {        
          // Shortcut for 3 element-sub-array
          final int a1 = array[low];
          final int a2 = array[low+1];
          final int a3 = array[high];

          if (a1 <= a2) 
          {
             if (a2 <= a3) 
                return;

             if (a3 <= a1)
             {
                array[low] = a3;
                array[low+1] = a1;
                array[high] = a2;
                return;
             }

             array[low+1] = a3;
             array[high] = a2;
          } 
          else 
          {
             if (a1 <= a3) 
             {
                array[low] = a2;
                array[low+1] = a1;
                return;
             }

             if (a3 <= a2) 
             {
                array[low] = a3;
                array[high] = a1;
                return;
             }

             array[low] = a2;
             array[low + 1] = a3;
             array[high] = a1;
          }

          return;
       }

       // Switch to insertion sort to avoid recursion
       for (int i=low+1; i<=high; i++) 
       {
          final int tmp = array[i];
          int j = i - 1;

          for ( ; ((j >= low) && (array[j] > tmp)); j--) 
             array[j+1] = array[j];

          array[j+1] = tmp;
       }
    }

}

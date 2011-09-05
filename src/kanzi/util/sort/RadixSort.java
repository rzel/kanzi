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

package kanzi.util.sort;

import java.util.LinkedList;
import kanzi.ByteSorter;
import kanzi.IntSorter;


// Fast implementation based on lists of buckets per radix
// See http://en.wikipedia.org/wiki/Radix_sort
// Radix sort complexity is O(kn) for n keys with (max) k digits per key
// This implementation uses a 4-bit radix
public final class RadixSort implements IntSorter, ByteSorter
{
    private static final int INT_THRESHOLD  = 0x10000000;
    private static final int BYTE_THRESHOLD = 0x00000010;

    private final LinkedQueue[] queues;
    private final int bufferSize;
    private final int size;
    private final int logDataSize;


    public RadixSort()
    {
        this.size = 0;
        this.bufferSize = 64;
        this.logDataSize = -1;
        this.queues = new LinkedQueue[16]; // radix of 16

        for (int i=0; i<this.queues.length; i++)
            this.queues[i] = new LinkedQueue(this.bufferSize);
    }


    public RadixSort(int size)
    {
        this(-1, size);
    }


    public RadixSort(int logDataSize, int size)
    {
        if (size < 0)
            throw new IllegalArgumentException("Invalid size parameter (must be a least 0)");

        if ((logDataSize != -1) && ((logDataSize < 4) || (logDataSize > 32)))
            throw new IllegalArgumentException("Invalid log data size parameter (must be in the [4, 32] range)");

        this.size = size;
        this.logDataSize = logDataSize;

        // Estimate a reasonable buffer size
        // Buffers contains data with 4 bit radix => 16 queues
        // Assuming 4 buffers per queue on average, each buffer contains on average
        // size >> (4 + 2) elements
        // Find log2(size >> 6)
        int log2 = 31 - Integer.numberOfLeadingZeros(size >> 6);
        int bufSize = 1 << (log2 + 1);

        // Avoid too small and big values
        if (bufSize < 16)
            bufSize = 16;
        else if (bufSize > 4096)
            bufSize = 4096;

        this.bufferSize = bufSize;
        this.queues = new LinkedQueue[16];

        for (int i=0; i<this.queues.length; i++)
            this.queues[i] = new LinkedQueue(this.bufferSize);
    }


    private void sort(int[] input, int blkptr, int digits)
    {
        final int sz = (this.size == 0) ? input.length : this.size;
        final int end = blkptr + sz;
        final int len = this.queues.length; // aliasing
        final int bSize = this.bufferSize; // aliasing

        for (int j=0; j<len; j++)
            this.queues[j].store((int[]) null);

        // Do a pass for each radix (4 bit step)
        for (int pass=0; pass<digits; pass++)
        {
            final int shift = pass << 2;

            for (int j=blkptr; j<end; j++)
            {
                final int value = input[j];
                final LinkedQueue queue = this.queues[(value >> shift) & 0x0F];

                // Add value to buffer
                queue.intBuffer[queue.index] = value;
                queue.index++;

                // Check if the previous buffer for this radix must be saved
                if (queue.index == bSize)
                   queue.store(queue.intBuffer);
            }

            int idx = blkptr;

            // Copy back data to the input array
            for (int j=0; j<len; j++)
               idx = this.queues[j].retrieve(input, idx);
        }

        LinkedQueue.clear();
    }


    // Not thread safe
    @Override
    public void sort(int[] input, int blkptr)
    {
        int digits;

        if (this.logDataSize < 0)
        {
            // Find max number of 4-bit radixes in the input data
            int max = 0;
            digits = 1;
            int sz = (this.size == 0) ? input.length : this.size;
            int end = blkptr + sz;

            for (int i=blkptr; i<end; i++)
            {
                int x = input[i];
                x = (x + (x >> 31)) ^ (x >> 31); // abs

                if (max < x)
                {
                    max = x;

                    if (max >= INT_THRESHOLD)
                        break;
                }
            }

            for (long cmp=16; cmp<max; digits++)
                cmp <<= 4;
        }
        else
        {
            digits = this.logDataSize >> 2;
        }

        this.sort(input, blkptr, digits);
    }


    private void sort(byte[] input, int blkptr, int digits)
    {
        final int sz = (this.size == 0) ? input.length : this.size;
        final int end = blkptr + sz;
        final int len = this.queues.length; // aliasing
        final int bSize = this.bufferSize; // aliasing

        for (int j=0; j<len; j++)
            this.queues[j].store((byte[]) null);

        // Do a pass for each radix (4 bit step)
        for (int pass=0; pass<digits; pass++)
        {
            final int shift = pass << 2;

            for (int j=blkptr; j<end; j++)
            {
                final byte value = input[j];
                final LinkedQueue queue = this.queues[(value >> shift) & 0x0F];

                // Add value to buffer
                queue.byteBuffer[queue.index] = value;
                queue.index++;

                // Check if the previous buffer for this radix must be saved
                if (queue.index == bSize)
                    queue.store(queue.byteBuffer);
            }

            int idx = blkptr;

            // Copy back data to the input array
            for (int j=0; j<len; j++)
               idx = this.queues[j].retrieve(input, idx);
        }

        LinkedQueue.clear();
    }


    // Not thread safe
    @Override
    public void sort(byte[] input, int blkptr)
    {
        // Find if one or 2 radix(es) must be processed
        int digits = 1;
        int sz = (this.size == 0) ? input.length : this.size;
        int end = blkptr + sz;

        for (int i=blkptr; i<end; i++)
        {
            if ((input[i] & 0xFF) >= BYTE_THRESHOLD)
            {
               digits = 2;
               break;
            }
         }

        this.sort(input, blkptr, digits);
    }



// ------ Utility classes ------


    private static class Node
    {
        Node next;
        Object value;

        Node()  { }

        Node(int[] array) { this.value = array; }

        Node(byte[] array) { this.value = array; }
    }


    private static class LinkedQueue
    {
        private static LinkedList<byte[]> POOL_B = new LinkedList<byte[]>();
        private static LinkedList<int[]>  POOL_I = new LinkedList<int[]>();

        private final Node head;
        private final int bufferSize;
        private Node tail;
        byte[] byteBuffer; // working buffer for int implementation
        int[] intBuffer;   // working buffer for byte implementation
        int index;         // index in working buffer


        public static void clear()
        {
            POOL_B.clear();
            POOL_I.clear();
        }

        
        public LinkedQueue(int bufferSize)
        {
           this.head = new Node();
           this.tail = this.head;
           this.bufferSize = bufferSize;
        }


        protected int[] store(int[] buffer)
        {
           if (buffer != null)
           {
              this.tail.next = new Node(buffer);
              this.tail = this.tail.next;
           }

           this.intBuffer = (POOL_I.size() == 0) ? new int[this.bufferSize] : POOL_I.removeFirst();
           this.index = 0;
           return this.intBuffer;
        }


        protected byte[] store(byte[] buffer)
        {
           if (buffer != null)
           {
             this.tail.next = new Node(buffer);
             this.tail = this.tail.next;
           }
           
           this.byteBuffer = (POOL_B.size() == 0) ? new byte[this.bufferSize] : POOL_B.removeFirst();
           this.index = 0;
           return this.byteBuffer;
        }


        public int retrieve(int[] array, int blkptr)
        {
            Node node = this.head.next;

            while (node != null)
            {
               int[] buffer = (int[]) node.value;
               System.arraycopy(buffer, 0, array, blkptr, buffer.length);
               blkptr += buffer.length;
               node.value = null;
               POOL_I.add(buffer); // recycle array
               node = node.next;
            }

            if (this.index < 32)
            {
               for (int i=this.index-1; i>=0; i--)
                   array[blkptr+i] = this.intBuffer[i];
            }
            else
            {
               System.arraycopy(this.intBuffer, 0, array, blkptr, this.index);
            }

            blkptr += this.index;
            this.index = 0;
            this.tail = this.head;
            this.tail.next = null;
            return blkptr;
        }


        public int retrieve(byte[] array, int blkptr)
        {
            Node node = this.head.next;

            while (node != null)
            {
               byte[] buffer = (byte[]) node.value;
               System.arraycopy(buffer, 0, array, blkptr, buffer.length);
               blkptr += buffer.length;
               node.value = null;
               POOL_B.add(buffer); // recycle array
               node = node.next;
            }

            if (this.index < 32)
            {
               for (int i=this.index-1; i>=0; i--)
                   array[blkptr+i] = this.byteBuffer[i];
            }
            else
            {
                System.arraycopy(this.byteBuffer, 0, array, blkptr, this.index);
            }

            blkptr += this.index;
            this.index = 0;
            this.tail = this.head;
            this.tail.next = null;
            return blkptr;
        }
    }
}

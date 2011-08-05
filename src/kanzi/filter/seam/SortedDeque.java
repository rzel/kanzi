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

package kanzi.filter.seam;

import kanzi.filter.seam.Geodesic;


// The goal is to have a limited but fast sorting deque of Geodesics for use in ContextResizer
// Not thread safe
@SuppressWarnings("unchecked")
/*package*/ class SortedDeque
{
    private final int maxSize;
    private int size;
    private Node head;
    private Node tail;
    private Node mid;


    public SortedDeque(int maxSize)
    {
        this.maxSize = maxSize;
    }


    // return index or -1 if the queue is full
    // null value is not allowed
    public int add(Geodesic value)
    {
        if (this.size == 0)
        {
           this.head = new Node(value);
           this.tail = this.head;
           this.mid = this.head;
           this.size = 1;
           return 0;
        }

        final int cost_ = value.cost; // aliasing
        Node current;
        int res;
        final int midPosition = this.size >> 1;

        if (cost_ >= this.tail.value.cost)
        {
           current = this.tail;
           res = 0;
        }
        else
        {
           if (cost_ <= this.mid.value.cost)
           {
              current = this.head;
              res = 0;
           }
           else
           {
              current = this.mid;
              res = midPosition;
           }

           while (current != null)
           {
             if (res == midPosition)
                this.mid = current;

             if (cost_ <= current.value.cost)
             {
                Node node = new Node(value);
                node.smaller = current.smaller;
                current.smaller = node;
                node.bigger = current;

                if (node.smaller != null)
                  node.smaller.bigger = node;
                else
                  this.head = node;

                if (this.size + 1 > this.maxSize)
                {
                  this.tail = this.tail.smaller;
                  this.tail.bigger.smaller = null;
                  this.tail.bigger = null;
                }
                else
                {
                  this.size++;
                }

                return res;
             }

             res++;
             current = current.bigger;
          }
        }

        // Add last element if the queue is not full
        if (this.size < this.maxSize)
        {
            Node node = new Node(value);
            node.smaller = this.tail;

            if (this.tail != null)
                this.tail.bigger = node;

            this.tail = node;
            this.size++;
            return this.size - 1;
        }

        return -1;
    }


    public boolean isFull()
    {
        return (this.size == this.maxSize);
    }


    public Geodesic getLast()
    {
        if (this.size == 0)
            return null;

        return this.tail.value;
    }


    public Geodesic getFirst()
    {
        if (this.size == 0)
            return null;

        return this.head.value;
    }


    public int size()
    {
        return this.size;
    }


    public Geodesic[] toArray(Geodesic[] array)
    {
        if (this.size == 0)
            return new Geodesic[0];

        if (array.length < this.size)
            array = new Geodesic[this.size];

        Node current = this.head;
        int idx = 0;

        while (current != null)
        {
            array[idx++] = current.value;
            current = current.bigger;
        }

        return array;
    }



    private static class Node
    {
        Node smaller;
        Node bigger;
        Geodesic value;

        Node(Geodesic value)
        {
            this.value = value;
        }
    }


    @Override
    public String toString()
    {
       StringBuilder builder = new StringBuilder((1+this.size)*8);
       Geodesic[] array = this.toArray(new Geodesic[this.size]);

       builder.append("Size=");
       builder.append(this.size);
       builder.append("\n");

       for (int i=0; i<array.length; i++)
       {
          if (i != 0)
             builder.append(",\n");

          builder.append(array[i]);
       }

       return builder.toString();
    }


}


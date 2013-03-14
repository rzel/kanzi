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

package kanzi.entropy;

import java.util.Comparator;
import kanzi.ArrayComparator;
import kanzi.util.sort.DefaultArrayComparator;
import kanzi.util.sort.QuickSort;
import java.util.LinkedList;
import java.util.TreeMap;
import kanzi.InputBitStream;
import kanzi.OutputBitStream;


// Tree utility class for a canonical implementation of Huffman codec
/*package*/ class HuffmanTree
{
    public static final int DECODING_BATCH_SIZE = 10; // in bits

    private final int[] codes;
    private final int[] sizes; // Cache for speed purpose
    private final Node root;
    private final CacheData[] decodingCache;
    private CacheData current;

    
    // Used by encoder
    /*package*/ HuffmanTree(int[] frequencies)
    {
       this.sizes = new int[256];
       this.decodingCache = null;
       this.current = null;

       // Create tree from frequencies
       this.root = this.createTreeFromFrequencies(frequencies);

       // Create canonical codes
       this.codes = generateCanonicalCodes(this.sizes);
    }


    // Used by decoder
    /*package*/ HuffmanTree(int[] sizes, int maxSize)
    {
       this.sizes = new int[256];
       System.arraycopy(sizes, 0, this.sizes, 0, sizes.length);

       // Create canonical codes
       this.codes = generateCanonicalCodes(this.sizes);

       // Create tree from code sizes
       this.root = createTreeFromSizes(maxSize, this.sizes, this.codes);
       this.decodingCache = createDecodingCache(this.root);
       this.current = new CacheData(this.root); // point to root
    }


    private static CacheData[] createDecodingCache(Node rootNode)
    {
       LinkedList<CacheData> nodes = new LinkedList<CacheData>();
       final int end = 1 << DECODING_BATCH_SIZE;
       CacheData previousData = null;

       // Create an array storing a list of Nodes for each input byte value
       for (int val=0; val<end; val++)
       {
          int shift = DECODING_BATCH_SIZE - 1;
          boolean firstAdded = false;

          while (shift >= 0)
          {
             // Start from root
             Node currentNode = rootNode;

             // Process next bit
             while ((shift >= 0) && ((currentNode.left != null) || (currentNode.right != null)))
             {
                currentNode = (((val >> shift) & 1) == 0) ? currentNode.left : currentNode.right;
                shift--;
             }

             final CacheData currentData = new CacheData(currentNode);

             // The list is made of linked nodes
             if (previousData != null)
                previousData.next = currentData;

             previousData = currentData;

             if (firstAdded == false)
             {
                // Add first node of list to array (whether it is a leaf or not)
                nodes.addLast(currentData);
                firstAdded = true;
             }
          }

          previousData.next = new CacheData(rootNode);
          previousData = previousData.next;
       }

       return nodes.toArray(new CacheData[nodes.size()]);
    }


    private Node createTreeFromFrequencies(int[] frequencies)
    {
       int[] array = new int[256];
       int n = 0;

       for (int i=0; i<array.length; i++)
       {
          if (frequencies[i] > 0)
             array[n++] = i;
       }
       
       // Sort by frequency
       QuickSort sorter = new QuickSort(new DefaultArrayComparator(frequencies));
       sorter.sort(array, 0, n);

       // Create Huffman tree of (present) symbols
       LinkedList<Node> queue1 = new LinkedList<Node>();
       LinkedList<Node> queue2 = new LinkedList<Node>();
       Node[] nodes = new Node[2];

       for (int i=n-1; i>=0; i--)
       {
          final int val = array[i];
          queue1.addFirst(new Node((byte) val, frequencies[val]));
       }

       while (queue1.size() + queue2.size() > 1)
       {
          // Extract 2 minimum nodes
          for (int i=0; i<2; i++)
          {
             if (queue1.size() == 0)
             {
                nodes[i] = queue2.removeFirst();
                continue;
             }

             if (queue2.size() == 0)
             {
                nodes[i] = queue1.removeFirst();
                continue;
             }

             if (queue1.getFirst().weight <= queue2.getFirst().weight)
                nodes[i] = queue1.removeFirst();
             else
                nodes[i] = queue2.removeFirst();
          }

          // Merge minimum nodes and enqueue result
          final Node left = nodes[0];
          final Node right = nodes[1];
          final Node merged = new Node(left.weight + right.weight, left, right);
          queue2.addLast(merged);
       }

       final Node rootNode = ((queue1.isEmpty()) ? queue2.removeFirst() : queue1.removeFirst());
       this.fillTree(rootNode, 0);
       return rootNode;
    }


    // Fill size and code arrays
    private void fillTree(Node node, int depth)
    {
       if ((node.left == null) && (node.right == null))
       {
          this.sizes[node.symbol & 0xFF] = depth;
          return;
       }

       if (node.left != null)
          this.fillTree(node.left, depth + 1);

       if (node.right != null)
          this.fillTree(node.right, depth + 1);
    }
    

    private static Node createTreeFromSizes(int maxSize, int[] lengths, int[] codes_)
    {
       TreeMap<Key, Node> codeMap = new TreeMap<Key, Node>(new KeyComparator());
       final int sum = 1 << maxSize;
       codeMap.put(new Key(0, 0), new Node((byte) 0, sum));

       // Create node for each (present) symbol and add to map
       for (int i=lengths.length-1; i>=0; i--)
       {
          final int size = lengths[i];

          if (size <= 0)
             continue;

          final Key key = new Key(size, codes_[i]);
          final Node value = new Node((byte) i, sum >> size);
          codeMap.put(key, value);
       }

       // Process each element of the map except the root node
       while (codeMap.size() > 1)
       {
          final Key key = codeMap.lastKey();
          final Node node = codeMap.remove(key);
          final Key upKey = new Key(key.length-1, (key.code >> 1) & 0xFF);
          Node upNode = codeMap.get(upKey);

          // Create superior node if it does not exist (length gap > 1)
          if (upNode == null)
          {
             upNode = new Node((byte) 0, sum >> upKey.length);
             codeMap.put(upKey, upNode);
          }

          // Add the current node to its parent at the correct place
          if ((key.code & 1) == 1)
             upNode.right = node;
          else
             upNode.left = node;
       }

       // Return the last element of the map (root node)
       return codeMap.firstEntry().getValue();
    }


    private static int[] generateCanonicalCodes(int[] lengths)
    {
       final int[] array = new int[lengths.length];
       int n = 0;

       for (int i=0; i<array.length; i++)
       {
          if (lengths[i] > 0)
             array[n++] = i;
       }
       
       // Sort by decreasing size (first key) and increasing value (second key)
       QuickSort sorter = new QuickSort(new HuffmanArrayComparator(lengths));
       sorter.sort(array, 0, n);

       final int[] codes_ = new int[256];
       int code = 0;
       int len = lengths[array[0]];

       for (int i=0; i<n; i++)
       {
          final int idx = array[i];
          final int currentSize = lengths[idx];

          while (len > currentSize)
          {
             code >>= 1;
             len--;
          }

          codes_[idx] = code;
          code++;
       }

       return codes_;
    }
    
    
    /*package*/ int getCode(int val)
    {
       return this.codes[val];
    }


    /*package*/ int getSize(int val)
    {
       return this.sizes[val];
    }


    /*package*/ boolean encodeByte(OutputBitStream bitstream, byte val)
    {
       final int idx = val & 0xFF;
       return (bitstream.writeBits(this.codes[idx], this.sizes[idx]) == this.sizes[idx]);
    }

    
    /*package*/ byte decodeByte(InputBitStream bitstream)
    {
       // Empty cache
       Node currNode = this.current.value;

       if (currNode != this.root)
          this.current = this.current.next;
       
       while ((currNode.left != null) || (currNode.right != null))
       {
          currNode = (bitstream.readBit() == 0) ? currNode.left : currNode.right;
       }

       return currNode.symbol;
    }


    // DECODING_BATCH_SIZE bits must be available in the bitstream
    /*package*/ byte fastDecodeByte(InputBitStream bitstream)
    {
       Node currNode = this.current.value;

       // Use the cache to find a good starting point in the tree
       if (currNode == this.root)
       {
          // Read more bits from the bitstream and fetch starting point from cache
          final int idx = (int) bitstream.readBits(DECODING_BATCH_SIZE);
          this.current = this.decodingCache[idx];
          currNode = this.current.value;
       }

       while ((currNode.left != null) || (currNode.right != null))
       {
          currNode = (bitstream.readBit() == 0) ? currNode.left : currNode.right;
       }

       this.current = this.current.next;
       return currNode.symbol;
    }


    // Huffman node
    private static class Node
    {
       protected final int weight;
       protected final byte symbol;
       protected Node left;
       protected Node right;


       // Leaf
       Node(byte symbol, int frequency)
       {
          this.weight = frequency;
          this.symbol = symbol;
       }


       // Not leaf
       Node(int frequency, Node node1, Node node2)
       {
          this.weight = frequency;
          this.symbol = 0;
          this.left  = node1;
          this.right = node2;
       }
    }


    // Class used to build the tree in canonical Huffman
    private static class Key
    {
       final int length;
       final int code;

       Key(int length, int code)
       {
          this.code = code;
          this.length = length;
       }
    }

    
    private static class KeyComparator implements Comparator<Key>
    {
       @Override
       public int compare(Key k1, Key k2) 
       {
          if (k1 == k2)
             return 0;
          
          if (k1 == null)
             return -1;
          
          if (k2 == null)
             return 1;
          
          final int len = k1.length - k2.length;

          if (len != 0)
             return len;

          return k1.code - k2.code;          
       }       
    }

    
    // Array comparator used to sort keys and values to generate canonical codes
    private static class HuffmanArrayComparator implements ArrayComparator
    {
        private final int[] array;
        

        public HuffmanArrayComparator(int[] array)
        {
            if (array == null)
                throw new NullPointerException("Invalid null array parameter");

            this.array = array;
        }


        @Override
        public int compare(int lidx, int ridx)
        {
            // Check sizes (reverse order) as first key
            final int res = this.array[ridx] - this.array[lidx];
            
            if (res != 0)
               return res;

            // Check value (natural order) as second key
            return lidx - ridx;
        }
    }


    private static class CacheData
    {
       Node value;
       CacheData next;

       CacheData(Node value)
       {
          this.value = value;
       }
    }
}
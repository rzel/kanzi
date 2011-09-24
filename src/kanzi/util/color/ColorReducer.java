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

package kanzi.util.color;

import java.util.Arrays;
import kanzi.VideoEffect;


// An octree based filter used to reduce 15, 16 or 24 bit color depth images
// to 8 bit color depth images
public class ColorReducer implements VideoEffect
{
  private final OctreeNode root;
  private final int initialDepth;
  private final int targetDepth;
  private final int downSamplingLog;
  private int[] buffer;


  public ColorReducer(int initialDepth, int targetDepth)
  {
    this(initialDepth, targetDepth, 1);
  }


  public ColorReducer(int initialDepth, int targetDepth, int downSampling)
  {
    if ((initialDepth != 24) && (initialDepth != 16) && (initialDepth != 15))
      throw new IllegalArgumentException("Invalid inital depth : " +
              initialDepth +  " must be 15, 16 or 24 bits");

    if ((targetDepth < 1) || (targetDepth > 8))
      throw new IllegalArgumentException("Invalid target depth : " +
              targetDepth +  " must be in the range [1..8] bits");

    if ((downSampling & (downSampling-1)) != 0)
      throw new IllegalArgumentException("Invalid down sampling factor (must be a power of 2)");

    int log2 = 0;

    for (int val2=downSampling; val2>1; val2>>=1)
      log2++;

    this.downSamplingLog = log2;
    this.targetDepth = targetDepth;
    this.initialDepth = initialDepth;
    this.root = new OctreeNode((short) (1 << targetDepth));
  }


  public int[] apply(int[] iRGB, int[] oRGB)
  {
    int shift = this.downSamplingLog;
    int inputLength = iRGB.length >> shift;
    int outputLength = 1 << this.targetDepth;

    // (Re)create local buffer if necessary
    if ((this.buffer == null) || (this.buffer.length < inputLength))
       this.buffer = new int[inputLength];

    // Copy input data to local buffer (so that the input array is not modified)
    if (shift < 1)
    {
      System.arraycopy(iRGB, 0, this.buffer, 0, inputLength);
    }
    else
    {
      for (int i=0; i<inputLength; i++)
        this.buffer[i] = iRGB[i<<shift];
    }

    // Remove duplicate colors
    Arrays.sort(this.buffer);
    int nn = 0;
    int prev = ~this.buffer[0];

    for (int i=0; i<inputLength; i++)
    {
       if (this.buffer[i] == prev)
          continue;

       prev = this.buffer[i];
       this.buffer[nn++] = prev;
    }

    inputLength = nn;

    // (Re)create output buffer if necessary
    if ((oRGB == null) || (oRGB.length < outputLength))
      oRGB = new int[outputLength];

    if (inputLength <= outputLength)
    {
      System.arraycopy(this.buffer, 0, oRGB, 0, inputLength);
    }
    else
    {
      // Build tree
      for (int i=0; i<inputLength; i++)
        this.root.addColor(this.buffer[i], this.initialDepth);

      int leaves = this.root.computeLeaves();

      while (leaves > outputLength)
        leaves -= this.root.removeOneLeaf();

      // Fill output array
      this.root.fillRGB(oRGB, this.initialDepth);

      // Clean up tree (free memory)
      for (int i=this.root.children.length-1; i>=0; i--)
          this.root.children[i] = null;

      this.root.nbChildren = 0;
    }

    return oRGB;
  }


  private static class OctreeNode
  {
     private final OctreeNode[] children;
     private final OctreeNode parent;
     private final short level;
     private final short maxLevel;
     private int references;
     private int red;
     private int green;
     private int blue;
     private short nbChildren;
     private short paletteIndex;
     private final byte parentIndex;


     public OctreeNode(short maxLevel)
     {
       this.maxLevel = maxLevel;
       this.children = new OctreeNode[8];
       this.paletteIndex = -1;
       this.level = 0;
       this.parentIndex = -1;
       this.parent = null;
     }


     protected OctreeNode(int red, int green, int blue, OctreeNode parent, int parentIndex)
     {
       if (parent == null)
           throw new NullPointerException("Invalid null parent");

       this.children = new OctreeNode[8];
       this.references = 1;
       this.red = red;
       this.green = green;
       this.blue = blue;
       this.parent = parent;
       this.level = (short) (parent.level + 1);
       this.maxLevel = parent.maxLevel;
       this.paletteIndex = -1;
       this.parentIndex = (byte) parentIndex;
     }


     protected void addColor(int rgb, int nbits)
     {
       if (nbits == 24)
       {
         this.addColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
       }
       else if (nbits == 16)
       {
         this.addColor((rgb >> 11) & 0x1F, (rgb >> 5) & 0x3F, rgb & 0x1F);
       }
       else if (nbits == 15)
       {
         this.addColor((rgb >> 10) & 0x1F, (rgb >> 5) & 0x1F, rgb & 0x1F);
       }
     }


     protected void addColor(int red, int green, int blue)
     {
       this.red   += red;
       this.green += green;
       this.blue  += blue;
       this.references++;

       if (this.level >= this.maxLevel)
         return;

       int mask = 0x80 >> this.level;
       int index = 0;

       if ((this.red & mask) != 0)
         index |= 4;

       if ((this.green & mask) != 0)
         index |= 2;

       if ((this.blue & mask) != 0)
         index |= 1;

       if (this.children[index] == null)
       {
         this.children[index] = new OctreeNode(red, green, blue, this, index);
         this.nbChildren++;
       }
       else
       {
         this.children[index].addColor(red, green, blue);
       }
     }


     // Remove leaf with most references
     // Return the number of leaves removed in the tree
     // It can be 1 if a leaf is removed and no new one is created in the parent
     // or 0 if one leaf is removed and a new one is created in the parent as result
     public int removeOneLeaf()
     {
       if (this.nbChildren == 0)
       {
         // This node is a leaf
         if (this.parent != null)
         {
           this.parent.children[this.parentIndex] = null;
           this.parent.nbChildren--;
           return (this.parent.nbChildren == 0) ? 0 : 1;
         }

         return 0;
       }

       // This node is not a leaf; find child with max references
       OctreeNode node = null;
       int max = Integer.MAX_VALUE;

       for (int i=this.children.length-1; i>=0; i--)
       {
         OctreeNode child = this.children[i];

         if ((child != null) && (child.references < max))
         {
            node = child;
            max = child.references;
         }
       }

       // node cannot be null otherwise this node is a leaf
       return node.removeOneLeaf();
     }


      public void fillRGB(int[] rgb, int nbits)
      {
        this.fillRGB(rgb, (short) 0, nbits );
      }


      private short fillRGB(int[] rgb, short index, int nbits)
      {
        if (this.nbChildren == 0)
        {
          this.paletteIndex = index;
          int rgbVal = 0;
          int ref = this.references;
          int adjust = ref >> 1;

          if (nbits == 24) // 8+8+8
          {
            rgbVal  = (((this.red  + adjust) / ref) & 0xFF) << 16;
            rgbVal |= (((this.green+ adjust) / ref) & 0xFF) << 8;
            rgbVal |= (((this.blue + adjust) / ref) & 0xFF);
          }
          else if (nbits == 16) // 5+6+5
          {
            rgbVal  = (((this.red  + adjust) / ref) & 0x1F) << 11;
            rgbVal |= (((this.green+ adjust) / ref) & 0x3F) << 5;
            rgbVal |= (((this.blue + adjust) / ref) & 0x1F);
          }
          else if(nbits == 15) // 5+5+5
          {
            rgbVal  = (((this.red  + adjust) / ref) & 0x1F) << 10;
            rgbVal |= (((this.green+ adjust) / ref) & 0x1F) << 5;
            rgbVal |= (((this.blue + adjust) / ref) & 0x1F);
          }

          rgb[this.paletteIndex] = rgbVal;
          return (short) (index+1);
        }
        else
        {
          for (int i=this.children.length-1; i>=0; i--)
          {
            OctreeNode child = this.children[i];

            if (child != null)
              index = child.fillRGB(rgb, index, nbits);
          }

          return index;
        }
      }


      public final int computeLeaves()
      {
        if (this.nbChildren == 0)
          return 1;

        int res = 0;

        for (int i=this.children.length-1; i>=0; i--)
        {
            OctreeNode child = this.children[i];

            if (child != null)
            {
              if (child.nbChildren == 0) // shortcut to avoid method calls
                res++;
              else
                res += child.computeLeaves();
            }
        }

        return res;
      }


      // Only valid when the tree has been correctly set and the fillRGB() method
      // has been called on the top root
      public int getIndexForColor(int red, int green, int blue)
      {
        int mask = 0x80 >> this.level;
        int index = 0;

        if ((red & mask) != 0)
          index |= 4;

        if ((green & mask) != 0)
          index |= 2;

        if ((blue & mask) != 0)
          index |= 1;

        if (this.children[index] != null)
          return this.children[index].getIndexForColor(red, green, blue);

        return this.paletteIndex;
      }
   }

}

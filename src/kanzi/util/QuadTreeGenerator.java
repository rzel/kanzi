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

package kanzi.util;

import java.util.Collection;
import java.util.TreeSet;


public class QuadTreeGenerator
{
    private final int width;
    private final int height;
    private final int stride;
    private final int offset;
    private final int nbNodes;
    private final int minNodeDim;

    
    public QuadTreeGenerator(int width, int height, int nbNodes)
    {
       this(width, height, 0, width, nbNodes, 8);
    }

   
    public QuadTreeGenerator(int width, int height, int nbNodes, int minNodeDim)
    {
       this(width, height, 0, width, nbNodes, minNodeDim);
    }

    
    public QuadTreeGenerator(int width, int height, int offset, int stride, int nbNodes, int minNodeDim)
    {
      if (height < 8)
         throw new IllegalArgumentException("The height must be at least 8");

      if (width < 8)
         throw new IllegalArgumentException("The width must be at least 8");

      if ((height & 1) != 0)
         throw new IllegalArgumentException("The height must be a multiple of 2");

      if ((width & 1) != 0)
         throw new IllegalArgumentException("The width must be a multiple of 2");

      if (nbNodes < 4)
         throw new IllegalArgumentException("The number of nodes must be at least 4");

      this.width = width;
      this.height = height;
      this.stride = stride;
      this.offset = offset;
      this.nbNodes = nbNodes;
      this.minNodeDim = minNodeDim;
   }   

    
   // Quad-tree decomposition of the input image based on variance of each node
   // The decomposition stops when enough nodes have been computed.
   // The centroid of each cluster is initialized at the center of the rectangle
   // pointed to by the nodes in the tree. It should provide a good initial
   // value for the centroids and help converge faster.
   public Collection<Node> decompose(Collection<Node> list, int[] buffer)
   {
      TreeSet<Node> processed = new TreeSet<Node>();
      TreeSet<Node> nodes = new TreeSet<Node>();
      nodes.addAll(list);
      final int w = this.width;
      final int h = this.height;

      // First level
      Node root1 = new Node(null, 0, 0, w>>1, h>>1);
      Node root2 = new Node(null, w>>1, 0, w>>1, h>>1);
      Node root3 = new Node(null, 0, h>>1, w>>1, h>>1);
      Node root4 = new Node(null, w>>1, h>>1, w>>1, h>>1);

      root1.computeVarianceRGB(buffer);
      root2.computeVarianceRGB(buffer);
      root3.computeVarianceRGB(buffer);
      root4.computeVarianceRGB(buffer);

      // Add to set of nodes sorted by decreasing variance
      nodes.add(root1);
      nodes.add(root2);
      nodes.add(root3);
      nodes.add(root4);
      
      while ((nodes.size() > 0) && (processed.size() + nodes.size() < this.nbNodes))
      {
         Node parent = nodes.pollFirst();

         if ((parent.w <= this.minNodeDim) || (parent.h <= this.minNodeDim))
         {
            processed.add(parent);
            continue;
         }

         // Create 4 children, taking into account odd dimensions
         final int pw = parent.w + 1;
         final int ph = parent.h + 1;
         final int cw = pw >> 1;
         final int ch = ph >> 1;
         Node node1 = new Node(parent, parent.x, parent.y, cw, ch);
         Node node2 = new Node(parent, parent.x+parent.w-cw, parent.y, cw, ch);
         Node node3 = new Node(parent, parent.x, parent.y+parent.h-ch, cw, ch);
         Node node4 = new Node(parent, parent.x+parent.w-cw, parent.y+parent.h-ch, cw, ch);

         node1.computeVarianceRGB(buffer);
         node2.computeVarianceRGB(buffer);
         node3.computeVarianceRGB(buffer);
         node4.computeVarianceRGB(buffer);
         
         // Add to set of nodes sorted by decreasing variance
         nodes.add(node1);
         nodes.add(node2);
         nodes.add(node3);
         nodes.add(node4);
      }

      nodes.addAll(processed);
      list.addAll(nodes);
      return list;
   }

   
   public class Node implements Comparable<Node>
   {
      final Node parent;
      public final int x;
      public final int y;
      public final int w;
      public final int h;
      public int variance;

      
      Node(Node parent, int x, int y, int w, int h)
      {
         this.parent = parent;
         this.x = x;
         this.y = y;
         this.w = w;
         this.h = h;
      }

      
      @Override
      public int compareTo(Node o)
      {
         // compare by decreasing variance
         final int val = o.variance - this.variance; 
         
         if (val != 0)
            return val;
         
         // In case of equal variance values, order is random
         return o.hashCode() ^ this.hashCode();
      }

      
      int computeVarianceRGB(int[] buffer)
      {
         final int iend = this.x + this.w;
         final int jend = this.y + this.h;
         final int len = this.w * this.h;
         long sq_sumR = 0, sq_sumB = 0, sq_sumG =0;
         long sumR = 0, sumG = 0, sumB = 0;
         final int st = QuadTreeGenerator.this.stride;
         int offs = (this.y * st) + QuadTreeGenerator.this.offset;

         for (int j=this.y; j<jend; j++)
         {
            for (int i=this.x; i<iend; i++)
            {
               final int pixel = buffer[offs+i];
               final int r = (pixel >> 16) & 0xFF;
               final int g = (pixel >>  8) & 0xFF;
               final int b =  pixel & 0xFF;
               sumR += r;
               sumG += g;
               sumB += b;
               sq_sumR += (r*r);
               sq_sumG += (g*g);
               sq_sumB += (b*b);
            }
            
            offs += st;
         }

         final long varR = (sq_sumR - ((sumR * sumR) / len)) / len;
         final long varG = (sq_sumG - ((sumG * sumG) / len)) / len;
         final long varB = (sq_sumB - ((sumB * sumB) / len)) / len;
         this.variance = (int) ((varR + varG + varB) / 3);
         return this.variance;
      }
      
      
      @Override
      public String toString()
      {
         StringBuilder builder = new StringBuilder(200);
         builder.append('[');
         builder.append("x=");
         builder.append(this.x);
         builder.append(", y=");
         builder.append(this.y);
         builder.append(", w=");
         builder.append(this.w);
         builder.append(", h=");
         builder.append(this.h);
         builder.append(", variance=");
         builder.append(this.variance);
         builder.append(']');
         return builder.toString();
      }
   }   
}

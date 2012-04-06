/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package kanzi.filter;

import java.util.Random;
import java.util.TreeSet;
import kanzi.VideoEffect;
import kanzi.util.QuadTreeGenerator;


// A filter that splits the image into patches of similar colors using k-means 
// clustering.
public class ColorClusterFilter implements VideoEffect
{
    private final int width;
    private final int height;
    private final int stride;
    private final int offset;
    private final int maxIterations;
    private final Cluster[] clusters;


    public ColorClusterFilter(int width, int height, int nbClusters)
    {
      this(width, height, 0, width, nbClusters);
    }

    
    public ColorClusterFilter(int width, int height, int nbClusters, int iterations)
    {
      this(width, height, 0, width, nbClusters, iterations);
    }


    public ColorClusterFilter(int width, int height, int offset, int stride, int nbClusters)
    {
       this(width, height, offset, stride, nbClusters, 16);
    }
    
    
    public ColorClusterFilter(int width, int height, int offset, int stride, int nbClusters, int iterations)
    {
      if (height < 8)
         throw new IllegalArgumentException("The height must be at least 8");

      if (width < 8)
         throw new IllegalArgumentException("The width must be at least 8");

      if ((height & 3) != 0)
         throw new IllegalArgumentException("The height must be a multiple of 4");

      if ((width & 3) != 0)
         throw new IllegalArgumentException("The width must be a multiple of 4");

      if ((nbClusters < 2) && (nbClusters > 128))
         throw new IllegalArgumentException("The number of clusters must be in [2..128]");

      if ((iterations < 2) || (iterations > 256))
         throw new IllegalArgumentException("The maximum number of iterations must be in [2..256]");

      this.width = width;
      this.height = height;
      this.stride = stride;
      this.offset = offset;
      this.maxIterations = iterations;
      this.clusters = new Cluster[nbClusters];

      for (int i=0; i<nbClusters; i++)
         this.clusters[i] = new Cluster();
    }


    // Use K-Means algorithm to create clusters of pixels with similar colors
    @Override
    public int[] apply(int[] src, int[] dst)
    {
       final int st = this.stride;
       int scale = 2;
       int scaledW = this.width >> scale;
       int scaledH = this.height >> scale;
       final Cluster[] cl = this.clusters;
       final int nbClusters = cl.length;
       final int rescaleThreshold = (this.maxIterations * 2 / 3);
       boolean rescaled = false;
       int iterations = 0;
       int moves;
       int offs = 0;

       // Create a down sampled copy of the source (1/4th in each dimension)
       this.createWorkImage(src, dst, scale);
       
       // Choose centers
       this.chooseCenters(this.clusters, dst, scaledW, scaledH);

       // Main loop, associate points to clusters and re-calculate centroids
       do
       {
         offs = 0;
         moves = 0;

         // Associate a pixel to the nearest cluster
         for (int j=0; j<scaledH; j++, offs+=st)
         {
            for (int i=0; i<scaledW; i++)
            {
               final int pixel = dst[offs+i];
               final int r = (pixel >> 16) & 0xFF;
               final int g = (pixel >>  8) & 0xFF;
               final int b =  pixel & 0xFF;
               int nearest = Integer.MAX_VALUE;
               int index = 0;

               for (int k=0; k<nbClusters; k++)
               {
                  final Cluster cluster = cl[k];
                  final int xc = cluster.centroidX;
                  final int yc = cluster.centroidY;
                  
                  // Distance is based on 3 color and 2 position coordinates
                  int sq_dist = 2 * ((i-xc)*(i-xc) + (j-yc)*(j-yc));
                  
                  if (sq_dist >= nearest) // early exit
                     continue;                  

                  final int rc = cluster.centroidR;
                  final int gc = cluster.centroidG;
                  final int bc = cluster.centroidB;

                  // Distance is based on 3 color and 2 position coordinates
                  sq_dist += (((r-rc)*(r-rc)) + ((g-gc)*(g-gc)) + ((b-bc)*(b-bc)));

                  if (sq_dist < nearest)
                  {
                     nearest = sq_dist;
                     index = k;
                  }
               }

               final Cluster cluster = cl[index];
               dst[offs+i] &= 0x00FFFFFF;
               dst[offs+i] |= ((index + 1) << 24); // update pixel's cluster index (top byte)
               cluster.sumR += r;
               cluster.sumG += g;
               cluster.sumB += b;
               cluster.sumX += i;
               cluster.sumY += j;
               cluster.items++;
            }
         }

         // Compute new centroid for each cluster
         for (int j=0; j<nbClusters; j++)
         {
            final Cluster cluster = cl[j];

            if (cluster.items == 0)
               continue;

            final int items = cluster.items;
            final int r = (cluster.sumR / items);
            final int g = (cluster.sumG / items);
            final int b = (cluster.sumB / items);
            final int newCentroidX = (cluster.sumX / items);
            final int newCentroidY = (cluster.sumY / items);

            if ((r != cluster.centroidR) || (g != cluster.centroidG)
                    || (b != cluster.centroidB) || (newCentroidX != cluster.centroidX)
                    || (newCentroidY != cluster.centroidY))
            {
              cluster.centroidR = r;
              cluster.centroidG = g;
              cluster.centroidB = b;
              cluster.centroidX = newCentroidX;
              cluster.centroidY = newCentroidY;
              moves++;
           }

           cluster.items = 0;
           cluster.sumR = 0;
           cluster.sumG = 0;
           cluster.sumB = 0;
           cluster.sumX = 0;
           cluster.sumY = 0;
         }

         iterations++;
         
         if ((rescaled == false) && ((iterations == rescaleThreshold) || (moves == 0)))
         {
            // Upscale to 1/2 in each dimension, now that centroids are somewhat stable
            scale >>= 1;
            scaledW = this.width >> scale;
            scaledH = this.height >> scale;
            this.createWorkImage(src, dst, scale);
            rescaled = true;
            
            for (int j=0; j<nbClusters; j++)
            {
               cl[j].centroidX <<= 1;
               cl[j].centroidY <<= 1;
            }
         }
      }
      while ((moves > 0) && (iterations < this.maxIterations));

      for (int j=0; j<nbClusters; j++)
      {
         final Cluster c = cl[j];
         c.centroidValue = (c.centroidR << 16) | (c.centroidG << 8) | c.centroidB;
      }
       
      return this.createFinalImage(src, dst);
   }

   
   // Create a down sampled copy of the source
   private int[] createWorkImage(int[] src, int[] dst, int scale)
   {
       final int scaledW = this.width >> scale;
       final int scaledH = this.height >> scale;
       final int st = this.stride;
       final int inc = 1 << scale;
       final int scale2 = scale + scale;
       final int adjust = 1 << (scale2 - 1);
       int srcIdx = this.offset;
       int dstIdx = 0;

       for (int j=0; j<scaledH; j++)
       {
          for (int i=0; i<scaledW; i++)
          {
             int idx = (srcIdx + i) << scale;
             int r = 0;
             int g = 0;
             int b = 0;
             
             // Take mean value of each pixel 
             for (int jj=0; jj<inc; jj++)
             {
                for (int ii=0; ii<inc; ii++)
                {
                   final int pixel = src[idx+ii];
                   r += ((pixel >> 16) & 0xFF);
                   g += ((pixel >>  8) & 0xFF);
                   b +=  (pixel & 0xFF);
                }
                
                idx += st;
             }
             
             r = (r + adjust) >> scale2;
             g = (g + adjust) >> scale2;
             b = (b + adjust) >> scale2;            
             dst[dstIdx+i] = ((r << 16) | (g << 8) | b) & 0x00FFFFFF;
          }
          
          srcIdx += st;
          dstIdx += st;
       }

       return dst;
   }

   
   // Up-sample and set all points in the cluster to color of the centroid pixel
   private int[] createFinalImage(int[] src, int[] dst)
   {
      final Cluster[] cl = this.clusters;
      final int scaledW = this.width >> 1;
      final int scaledY = this.height >> 1;
      final int st = this.stride;
      int offs = (scaledY - 1) * st;
      int nlOffs = offs;

      for (int j=scaledY-1; j>=0; j--, offs-=st)
      {    
         Cluster c1 = cl[(dst[offs]>>>24)-1]; // pixel p1 to the right of current p0
         Cluster c2 = cl[(dst[nlOffs]>>>24)-1]; // pixel p2 below current  p0
         Cluster c3 = c2; // pixel p3 to the right of p2
         
         for (int i=scaledW-1; i>=0; i--)
         {
            int idx = ((offs + i) << 1) + this.offset;
            final int cluster0Idx = (dst[offs+i] >>> 24) - 1;
            final Cluster c0 = cl[cluster0Idx];
            final int pixel0 = c0.centroidValue;
            dst[idx] = pixel0;   
                       
            if (c0 == c3)
            {
              // Inside cluster
              dst[idx+st+1] = pixel0;
            }
            else
            {
               // Diagonal cluster border
               final int pixel = src[idx+st+1];
               final int r = (pixel >> 16) & 0xFF;
               final int g = (pixel >>  8) & 0xFF;
               final int b =  pixel & 0xFF;
               final int d0 = ((r-c0.centroidR)*(r-c0.centroidR)
                             + (g-c0.centroidG)*(g-c0.centroidG)
                             + (b-c0.centroidB)*(b-c0.centroidB));
               final int d3 = ((r-c3.centroidR)*(r-c3.centroidR)
                             + (g-c3.centroidG)*(g-c3.centroidG)
                             + (b-c3.centroidB)*(b-c3.centroidB));              
               dst[idx+st+1] = (d0 < d3) ? pixel0 : c3.centroidValue;
            }

            if (c0 == c2)
            {
              // Inside cluster
              dst[idx+st] = pixel0;
            }
            else
            {
               // Vertical cluster border
               final int pixel = src[idx+st];
               final int r = (pixel >> 16) & 0xFF;
               final int g = (pixel >>  8) & 0xFF;
               final int b =  pixel & 0xFF;
               final int d0 = ((r-c0.centroidR)*(r-c0.centroidR)
                             + (g-c0.centroidG)*(g-c0.centroidG)
                             + (b-c0.centroidB)*(b-c0.centroidB));
               final int d2 = ((r-c2.centroidR)*(r-c2.centroidR)
                             + (g-c2.centroidG)*(g-c2.centroidG)
                             + (b-c2.centroidB)*(b-c2.centroidB));              
               dst[idx+st] = (d0 < d2) ? pixel0 : c2.centroidValue;
            }

            if (c0 == c1)
            {
              // Inside cluster
              dst[idx+1] = pixel0;
            }
            else
            {
               // Horizontal cluster border
               final int pixel = src[idx+1];
               final int r = (pixel >> 16) & 0xFF;
               final int g = (pixel >>  8) & 0xFF;
               final int b =  pixel & 0xFF;
               final int d0 = ((r-c0.centroidR)*(r-c0.centroidR)
                             + (g-c0.centroidG)*(g-c0.centroidG)
                             + (b-c0.centroidB)*(b-c0.centroidB));
               final int d1 = ((r-c1.centroidR)*(r-c1.centroidR)
                             + (g-c1.centroidG)*(g-c1.centroidG)
                             + (b-c1.centroidB)*(b-c1.centroidB));              
               dst[idx+1] = (d0 < d1) ? pixel0 : c1.centroidValue;
            }
            
            nlOffs = offs;
            c1 = c0;
            c3 = c2;
         }

      }

      return dst;
   }


   // Quad-tree decomposition of the input image based on variance of each node
   // The decomposition stops when enough nodes have been computed.
   // The centroid of each cluster is initialized at the center of the rectangle
   // pointed to by the nodes in the tree. It should provide a good initial
   // value for the centroids and help converge faster.
   private void chooseCenters(Cluster[] clusters, int[] buffer, int ww, int hh)
   {
      // Create quad tree decompoisition of the image
      TreeSet<QuadTreeGenerator.Node> nodes = new TreeSet<QuadTreeGenerator.Node>();
      QuadTreeGenerator qtg = new QuadTreeGenerator(ww, hh, 0, this.stride, clusters.length, 8);
      qtg.decompose(nodes, buffer);
      int n = clusters.length-1;

      while ((n >= 0) && (nodes.size() > 0))
      {
         QuadTreeGenerator.Node next = nodes.first();
         nodes.remove(next);
         Cluster c = clusters[n];
         c.centroidX = next.x + (next.w >> 1);
         c.centroidY = next.y + (next.h >> 1);
         final int centroidValue = buffer[(c.centroidY * this.stride) + c.centroidX];
         c.centroidR = (centroidValue >> 16) & 0xFF;
         c.centroidG = (centroidValue >>  8) & 0xFF;
         c.centroidB =  centroidValue & 0xFF;
         n--;
     }
      
     if (n > 0)
     {
       // If needed, other centroids are set to random values
       Random rnd = new Random();
       
       while (n >= 0) 
       {
          Cluster c = clusters[n];
          c.centroidX = rnd.nextInt(ww);
          c.centroidY = rnd.nextInt(hh);
          final int centroidValue = buffer[(c.centroidY * this.stride) + c.centroidX];
          c.centroidR = (centroidValue >> 16) & 0xFF;
          c.centroidG = (centroidValue >>  8) & 0xFF;
          c.centroidB =  centroidValue & 0xFF;
          n--;
       }
     }    
   }


   private static class Cluster
   {
      int items;
      int centroidR;
      int centroidG;
      int centroidB;
      int centroidX;
      int centroidY;
      int centroidValue;
      int sumR;
      int sumG;
      int sumB;
      int sumX;
      int sumY;
   }

}

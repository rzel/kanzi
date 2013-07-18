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

package kanzi.filter.seam;


import kanzi.VideoEffect;
import kanzi.IntSorter;
import kanzi.filter.SobelFilter;
import kanzi.util.sort.BucketSort;
import kanzi.util.sort.RadixSort;


// Based on algorithm by Shai Avidan, Ariel Shamir
// Described in [Seam Carving for Content-Aware Image Resizing]
//
// This implementation is focused on speed and is indeed very fast (but it does
// only calculate an approximation of the energy minimizing paths because finding
// the absolute best paths takes too much time)
// It is also possible to calculate the seams on a subset of the image which is
// useful to iterate over the same (shrinking) image.
//
// Note: the name seam carving is a bit unfortunate, what the algo achieves
// is detection and removal of the paths of least resistance (energy wise) in
// the image. These paths really are geodesics.
public class ContextResizer implements VideoEffect
{
    // Possible directions
    public static final int HORIZONTAL = 1;
    public static final int VERTICAL = 2;

    // Possible actions
    public static final int SHRINK = 1;
    public static final int EXPAND = 2;

    private static final int USED_MASK = 0x80000000;
    private static final int VALUE_MASK = USED_MASK - 1;
    private static final int DEFAULT_BEST_COST = 0x0FFFFFFF;
    private static final int DEFAULT_MAX_COST_PER_PIXEL = 256;
    private static final int RED_COLOR = 0xFFFF0000;
    private static final int BLUE_COLOR = 0xFF0000FF;

    private int width;
    private int height;
    private final int stride;
    private final int direction;
    private final int maxSearches;
    private final int maxAvgGeoPixCost;
    private final int[] costs;
    private final int nbGeodesics;
    private int offset;
    private int action;
    private boolean debug;
    private final IntSorter sorter;
    private int[] buffer;
    private final int sobelMode;


    public ContextResizer(int width, int height, int direction, int action)
    {
        this(width, height, 0, width, direction, action, 1, 1, false);
    }


    // width, height, offset and stride allow to apply the filter on a subset of an image
    // For packed RGB images, use 3 channels mode for more accurate results (fastMode=false)
    // and one channel mode (fastMode=true) for faster results.  
    // For unpacked images, use one channel mode (fastMode=true).
    public ContextResizer(int width, int height, int offset, int stride,
            int direction, int action, int maxSearches, int nbGeodesics,
            boolean fastMode)
    {
        this(width, height, offset, stride, direction, action, maxSearches,
                nbGeodesics, fastMode, DEFAULT_MAX_COST_PER_PIXEL);
    }


    // width, height, offset and stride allow to apply the filter on a subset of an image
    // maxAvgGeoPixCost allows to limit the cost of geodesics: only those with an
    // average cost per pixel less than maxAvgGeoPixCost are allowed (it may be
    // less than nbGeodesics).
    // For packed RGB images, use 3 channels mode for more accurate results (fastMode=false)
    // and one channel mode (fastMode=true) for faster results.  
    // For unpacked images, use one channel mode (fastMode=true).
    public ContextResizer(int width, int height, int offset, int stride,
            int direction, int action, int maxSearches, int nbGeodesics,
             boolean fastMode, int maxAvgGeoPixCost)
    {
        if (height < 8)
            throw new IllegalArgumentException("The height must be at least 8");

        if (width < 8)
            throw new IllegalArgumentException("The width must be at least 8");

        if (offset < 0)
            throw new IllegalArgumentException("The offset must be at least 0");

        if (stride < 8)
            throw new IllegalArgumentException("The stride must be at least 8");

        if (maxAvgGeoPixCost < 1)
            throw new IllegalArgumentException("The max average pixel cost in a geodesic must be at least 1");

        if (nbGeodesics < 1)
            throw new IllegalArgumentException("The number of geodesics must be at least 1");

        if (((direction & HORIZONTAL) == 0) && ((direction & VERTICAL) == 0))
            throw new IllegalArgumentException("Invalid direction parameter (must be VERTICAL or HORIZONTAL)");

        if ((action != SHRINK) && (action != EXPAND))
            throw new IllegalArgumentException("Invalid action parameter (must be SHRINK or EXPAND)");

        if ((direction & HORIZONTAL) == 0)
        {
           if (nbGeodesics > width)
              throw new IllegalArgumentException("The number of geodesics must be at most "+width);

           if ((maxSearches < 1) || (maxSearches > width))
              throw new IllegalArgumentException("The number of checks must be in the [1.."+width+"] range");
        }
        else
        {
           if (nbGeodesics > height)
              throw new IllegalArgumentException("The number of geodesics must be at most "+height);
   
           if ((maxSearches < 1) || (maxSearches > height))
              throw new IllegalArgumentException("The number of checks must be in the [1.."+height+"] range");
        }

        this.height = height;
        this.width = width;
        this.offset = offset;
        this.stride = stride;
        this.direction = direction;
        this.maxSearches = maxSearches;
        this.costs = new int[stride*height];
        this.nbGeodesics = nbGeodesics;
        this.maxAvgGeoPixCost = maxAvgGeoPixCost;
        this.action = action;
        this.buffer = new int[0];
        this.sobelMode = (fastMode == true) ? SobelFilter.ONE_CHANNEL : SobelFilter.THREE_CHANNELS;
        int dim = (height >= width) ? height : width;
        int log = 0;

        for (long val=dim+1; val>1; val>>=1)
          log++;

        if ((dim & (dim-1)) != 0)
            log++;

        // Used to sort coordinates of geodesics
        this.sorter = (log < 12) ? new BucketSort(log) : new RadixSort(8, log);
    }


    public int getWidth()
    {
        return this.width;
    }


    public int getHeight()
    {
        return this.height;
    }


    public boolean getDebug()
    {
        return this.debug;
    }


    // Not thread safe
    public boolean setDebug(boolean debug)
    {
        this.debug = debug;
        return true;
    }


    public int getOffset()
    {
        return this.offset;
    }


    // Not thread safe
    public boolean setOffset(int offset)
    {
        if (offset < 0)
            return false;

        this.offset = offset;
        return true;
    }


    public int getAction()
    {
        return this.action;
    }


    // Not thread safe
    public boolean setAction(int action)
    {
        if ((action != SHRINK) && (action != EXPAND))
            return false;

        this.action = action;
        return true;
    }


    public int[] shrink(int[] src, int[] dst)
    {
        this.setAction(SHRINK);
        return this.shrink_(src, dst);
    }


    public int[] expand(int[] src, int[] dst)
    {
        this.setAction(EXPAND);
        return this.expand_(src, dst);
    }


    // Will modify the width and/or height attributes
    // The src image is modified if both directions are selected
    @Override
    public int[] apply(int[] src, int[] dst)
    {
       return (this.action == SHRINK) ? this.shrink_(src, dst) : this.expand_(src, dst);
    }


    // Will increase the width and/or height attributes. Result must fit in width*height
    private int[] expand_(int[] src, int[] dst)
    {
        int processed = 0;
        int[] input = src;
        int[] output = dst;

        if ((this.direction & VERTICAL) != 0)
        {
            if ((this.direction & HORIZONTAL) != 0)
            {
               // Lazy dynamic memory allocation
               if (this.buffer.length < this.width * this.height)
                  this.buffer = new int[this.width*this.height];
               
               output = this.buffer;              
            }
            
            Geodesic[] geodesics = this.computeGeodesics(input, VERTICAL);
 
            if (geodesics.length > 0)
            {
                processed += geodesics.length;
                this.addGeodesics(geodesics, input, output, VERTICAL);
            }
           
            if ((this.direction & HORIZONTAL) != 0)
            {
               input = this.buffer;
               output = dst;
            }        
        }

        if ((this.direction & HORIZONTAL) != 0)
        {
            Geodesic[] geodesics = this.computeGeodesics(input, HORIZONTAL);

            if (geodesics.length > 0)
            {
                processed += geodesics.length;
                this.addGeodesics(geodesics, input, output, HORIZONTAL);
            }
        }

        if ((processed == 0) && (src != dst))
        {
           System.arraycopy(src, this.offset, dst, this.offset, this.height*this.stride);
        }

        return dst;
    }


    // Will decrease the width and/or height attributes
    private int[] shrink_(int[] src, int[] dst)
    {
        int processed = 0;
        int[] input = src;
        int[] output = dst;

        if ((this.direction & VERTICAL) != 0)
        {
            if ((this.direction & HORIZONTAL) != 0)
            {
               // Lazy dynamic memory allocation
               if (this.buffer.length < this.width * this.height)
                  this.buffer = new int[this.width*this.height];
               
               output = this.buffer;              
            }
            
            Geodesic[] geodesics = this.computeGeodesics(input, VERTICAL);

            if (geodesics.length > 0)
            {
               processed += geodesics.length;
               this.removeGeodesics(geodesics, input, output, VERTICAL);
            }
            
            if ((this.direction & HORIZONTAL) != 0)
            {
               input = this.buffer;
               output = dst;
            }
        }

        if ((this.direction & HORIZONTAL) != 0)
        {
            Geodesic[] geodesics = this.computeGeodesics(input, HORIZONTAL);

            if (geodesics.length > 0)
            {
               processed += geodesics.length;
               this.removeGeodesics(geodesics, input, output, HORIZONTAL);
            }   
        }

        if ((processed == 0) && (src != dst))
        {
           System.arraycopy(src, this.offset, dst, this.offset, this.height*this.stride);
        }

        return dst;
    }


    // dir must be either VERTICAL or HORIZONTAL
    public boolean addGeodesics(Geodesic[] geodesics, int[] src, int[] dst, int dir)
    {
        if (((dir & VERTICAL) == 0) && ((dir & HORIZONTAL) == 0))
           return false;
               
        if (((dir & VERTICAL) != 0) && ((dir & HORIZONTAL) != 0))
           return false;
        
        if (geodesics.length == 0)
           return true ;

        int srcStart = this.offset;
        int dstStart = this.offset;
        final int[] linePositions = new int[geodesics.length];
        final int endj;
        final int endi;
        final int incStart;
        final int incIdx;
        final int color;

        if (dir == HORIZONTAL)
        {
            endj = this.width;
            endi = this.height;
            incStart = 1;
            incIdx = this.stride;
            color = BLUE_COLOR;
        }
        else
        {
            endj = this.height;
            endi = this.width;
            incStart = this.stride;
            incIdx = 1;
            color = RED_COLOR;           
        }

        for (int j=endj-1; j>=0; j--)
        {
            // Find all the pixels belonging to geodesics in this line
            for (int k=0; k<linePositions.length; k++)
                linePositions[k] = geodesics[k].positions[j];

            // Sort the pixels by increasing position
            if (linePositions.length > 1)
                this.sorter.sort(linePositions, 0, linePositions.length);

            int posIdx = 0;
            int srcIdx = srcStart;
            int dstIdx = dstStart;
            final int endPosIdx = linePositions.length;
            int pos = 0;
            
            while (posIdx < endPosIdx)
            {
                int newPos = linePositions[posIdx];
                final int len = newPos - pos;
                
                if (len > 0)
                {     
                   if ((dir == VERTICAL) && (len >= 32))
                   {
                       // Speed up copy
                       System.arraycopy(src, srcIdx, dst, dstIdx, len);
                       srcIdx += len;
                       dstIdx += len;
                    }
                    else
                    {
                       copy(src, srcIdx, dst, dstIdx, len, incIdx);
                       srcIdx += (len * incIdx);
                       dstIdx += (len * incIdx);
                    }
                
                   pos = newPos;
                }

                // Insert new pixel into the destination
                if (this.debug == true)
                {
                   dst[dstIdx] = color;
                }
                else
                {
                   final int pix = src[srcIdx]; 
                   final int r = (pix >> 16) & 0xFF;
                   final int g = (pix >> 8)  & 0xFF;
                   final int b =  pix & 0xFF;
                   dst[dstIdx] = (r << 16) | (g << 8) | b;
                }

                pos++;
                dstIdx += incIdx;
                posIdx++;
            }

            final int len = endi - pos;
            
            // Finish the line, no more test for geodesic pixels required
            if ((dir == VERTICAL) && (len >= 32))
            {
               // Speed up copy
               System.arraycopy(src, srcIdx, dst, dstIdx, len);
               srcIdx += len;
               dstIdx += len;
            }
            else
            {
               // Either incIdx != 1 or not enough pixels for arraycopy to be worth it
               copy(src, srcIdx, dst, dstIdx, len, incIdx);
               srcIdx += (len * incIdx);
               dstIdx += (len * incIdx);
            }

            srcStart += incStart;
            dstStart += incStart;
        }
        
        return true;
    }
    
    
    // dir must be either VERTICAL or HORIZONTAL
    public boolean removeGeodesics(Geodesic[] geodesics, int[] src, int[] dst, int dir)
    {
        if (((dir & VERTICAL) == 0) && ((dir & HORIZONTAL) == 0))
           return false;
               
        if (((dir & VERTICAL) != 0) && ((dir & HORIZONTAL) != 0))
           return false;
               
        if (geodesics.length == 0)
           return true;

        final int[] linePositions = new int[geodesics.length];
        final int endj;
        final int endLine;
        final int incIdx;
        final int incStart;
        final int color;

        if (dir == HORIZONTAL)
        {
            endj = this.width;
            endLine = this.height;
            incIdx = this.stride;
            incStart = 1;
            color = BLUE_COLOR;
        }
        else
        {
            endj = this.height;
            endLine = this.width;
            incIdx = 1;
            incStart = this.stride;
            color = RED_COLOR;
        }

        int srcStart = this.offset;
        int dstStart = this.offset;

        for (int j=0; j<endj; j++)
        {
            // Find all the pixels belonging to geodesics in this line
            for (int k=0; k<linePositions.length; k++)
                linePositions[k] = geodesics[k].positions[j];

            // Sort the pixels by increasing position
            if (linePositions.length > 1)
                this.sorter.sort(linePositions, 0, linePositions.length);

            int srcIdx = srcStart;
            int dstIdx = dstStart;
            int posIdx = 0;
            final int endPosIdx = linePositions.length;
            int pos = 0;

            while (posIdx < endPosIdx)
            {
                final int nextPos = linePositions[posIdx];
                final int len = nextPos - pos;

                // Copy pixels not belonging to a geodesic
                if (len > 0)
                {
                    if ((dir == VERTICAL) && (len >= 32))
                    {
                       // Speed up copy
                       System.arraycopy(src, srcIdx, dst, dstIdx, len);
                       srcIdx += len;
                       dstIdx += len;
                    }
                    else
                    {
                       // Either incIdx != 1 or not enough pixels for arraycopy to be worth it
                       copy(src, srcIdx, dst, dstIdx, len, incIdx);
                       srcIdx += (len * incIdx);
                       dstIdx += (len * incIdx);
                    }

                    pos = nextPos;
                }

                // Mark or remove pixel belonging to a geodesic
                if (this.debug == true)
                {
                    dst[dstIdx] = color;
                    dstIdx += incIdx;
                }

                pos++;
                srcIdx += incIdx;
                posIdx++;
            }

            final int len = endLine - pos;

            // Finish the line, no more test for geodesic pixels required
            if ((dir == VERTICAL) && (len >= 32))
            {
               // Speed up copy
               System.arraycopy(src, srcIdx, dst, dstIdx, len);
               srcIdx += len;
               dstIdx += len;
            }
            else
            {
               // Either incIdx != 1 or not enough pixels for arraycopy to be worth it
               copy(src, srcIdx, dst, dstIdx, len, incIdx);
               srcIdx += (len * incIdx);
               dstIdx += (len * incIdx);
            }

            srcStart += incStart;
            dstStart += incStart;
        }

        return true;
    }


    // dir must be either VERTICAL or HORIZONTAL
    public Geodesic[] computeGeodesics(int[] src, int dir)
    {          
        if (((dir & VERTICAL) == 0) && ((dir & HORIZONTAL) == 0))
           return new Geodesic[0];
               
        if (((dir & VERTICAL) != 0) && ((dir & HORIZONTAL) != 0))
           return new Geodesic[0];

        final int dim = (dir == HORIZONTAL) ? this.height : this.width;
        int[] firstPositions = new int[this.maxSearches];
        int n = 0;

        // Spread the first position along 'direction' for better uniformity
        // Should improve speed by detecting faster low cost paths and reduce
        // geodesic crossing management.
        // It will improve quality by spreading the search over the whole image
        // if maxSearches is small.
        for (int i=0; ((n<this.maxSearches) && (i<24)); i+=3)
        {
            // i & 7 shuffles the start position : 0, 3, 6, 1, 4, 7, 2, 5
            for (int j=(i & 7); ((n<this.maxSearches) && (j<dim)); j+=8)
                firstPositions[n++] = j;
        }

        return this.computeGeodesics_(src, dir, firstPositions, this.maxSearches);
    }


    // Compute the geodesics but give a constraint on where to start from
    // All first position values must be different
    // dir must be either VERTICAL or HORIZONTAL
    public Geodesic[] computeGeodesics(int[] src, int dir, int[] firstPositions)
    {
        if (((dir & VERTICAL) == 0) && ((dir & HORIZONTAL) == 0))
           return new Geodesic[0];
               
        if (((dir & VERTICAL) != 0) && ((dir & HORIZONTAL) != 0))
           return new Geodesic[0];

        return this.computeGeodesics_(src, dir, firstPositions, this.maxSearches);
    }


    private Geodesic[] computeGeodesics_(int[] src, int dir, int[] firstPositions, int maxSearches)
    {
        if ((maxSearches == 0) || (src == null) || (firstPositions == null))
            return new Geodesic[0];

        // Limit searches if there are not enough starting positions
        if (maxSearches > firstPositions.length)
            maxSearches = firstPositions.length;

        final int geoLength;
        final int inc;
        final int incLine;
        final int lineLength;

        if (dir == HORIZONTAL)
        {
            geoLength = this.width;
            lineLength = this.height;
            inc = this.stride;
            incLine = 1;
        }
        else
        {
            geoLength = this.height;
            lineLength = this.width;
            inc = 1;
            incLine = this.stride;
        }

        // Calculate cost at each pixel
        this.calculateCosts(src, this.costs);
        final int maxGeo = (this.nbGeodesics > maxSearches) ? maxSearches : this.nbGeodesics;

        // Queue of geodesics sorted by cost
        // The queue size could be less than firstPositions.length
        final GeodesicSortedQueue queue = new GeodesicSortedQueue(maxGeo);
        boolean consumed = true;
        Geodesic geodesic = null;
        Geodesic last = null; // last in queue
        int maxCost = geoLength * this.maxAvgGeoPixCost;
        final int[] costs_ = this.costs; // aliasing

        // Calculate path and cost for each geodesic
        for (int i=0; i<maxSearches; i++)
        {
            if (consumed == true)
                geodesic = new Geodesic(dir, geoLength);

            consumed = false;
            int bestLinePos = firstPositions[i];
            int costIdx = this.offset + (inc * bestLinePos);
            geodesic.positions[0] = bestLinePos;
            geodesic.cost = costs_[costIdx];

            // Process each row/column
            for (int pos=1; pos<geoLength; pos++)
            {
                costIdx += incLine;
                final int startCostIdx = costIdx;
                int startBestLinePos = bestLinePos;
                int  bestCost = ((costs_[startCostIdx] & USED_MASK) == 0) ? costs_[startCostIdx]
                        : DEFAULT_BEST_COST;

                if (bestCost > 0)
                {
                    // Check left/upper pixel, skip already used pixels
                    int idx = startCostIdx - inc;

                    for (int linePos=startBestLinePos-1; linePos>=0; idx-=inc, linePos--)
                    {
                        final int cost = costs_[idx];

                        // Skip pixels in use
                        if ((cost & USED_MASK) != 0)
                           continue;

                        if (cost < bestCost)
                        {
                            bestCost = cost;
                            bestLinePos = linePos;
                            costIdx = idx;
                        }

                        break;
                    }
                }

                if (bestCost > 0)
                {
                    // Check right/lower pixel, skip already used pixels
                    int idx = startCostIdx + inc;

                    for (int linePos=startBestLinePos+1; linePos<lineLength; idx+=inc, linePos++)
                    {
                        final int cost = costs_[idx];

                        if ((cost & USED_MASK) != 0)
                           continue;

                         if (cost < bestCost)
                         {
                             bestCost = cost;
                             bestLinePos = linePos;
                             costIdx = idx;
                         }

                         break;
                    }

                    geodesic.cost += bestCost;

                    // Skip, this path is already too expensive
                    if (geodesic.cost >= maxCost)
                       break;
                }

                geodesic.positions[pos] = bestLinePos;
            }

            if (geodesic.cost < maxCost)
            {
                 final int geoLength4 = geoLength & -4;

                 // Add geodesic (in increasing cost order). It is sure to succeed
                 // (it may evict the current tail) because geodesic.cos < maxCost
                 // and maxCost is adjusted to tail.value
                 Geodesic newLast = queue.add(geodesic);

                 // Prevent geodesics from sharing pixels by marking the used pixels
                 // Only the pixels of the geodesics in the queue are marked as used
                 if (this.nbGeodesics > 1)
                 {
                     // If the previous last element has been expelled from the queue,
                     // the corresponding pixels can be reused by other geodesics
                     int startLine = this.offset;
                     final int[] gp = geodesic.positions;

                     if (last != null)
                     {
                        final int[] lp = last.positions;

                        // Tag old pixels as 'free' and new pixels as 'used'
                        for (int k=0; k<geoLength4; k+=4)
                        {
                            costs_[startLine+(inc*gp[k])]   |= USED_MASK;
                            costs_[startLine+(inc*lp[k])]   &= VALUE_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+1])] |= USED_MASK;
                            costs_[startLine+(inc*lp[k+1])] &= VALUE_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+2])] |= USED_MASK;
                            costs_[startLine+(inc*lp[k+2])] &= VALUE_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+3])] |= USED_MASK;
                            costs_[startLine+(inc*lp[k+3])] &= VALUE_MASK;
                            startLine += incLine;
                        }

                        for (int k=geoLength4; k<geoLength; k++)
                        {
                            costs_[startLine+(inc*gp[k])] |= USED_MASK;
                            costs_[startLine+(inc*lp[k])] &= VALUE_MASK;
                            startLine += incLine;
                        }
                     }
                     else
                     {
                        for (int k=0; k<geoLength4; k+=4)
                        {
                            costs_[startLine+(inc*gp[k])]   |= USED_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+1])] |= USED_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+2])] |= USED_MASK;
                            startLine += incLine;
                            costs_[startLine+(inc*gp[k+3])] |= USED_MASK;
                            startLine += incLine;
                        }

                        for (int k=geoLength4; k<geoLength; k++)
                        {
                            costs_[startLine+(inc*gp[k])] |= USED_MASK;
                            startLine += incLine;
                        }
                     }
                 }

                 // Be green, recycle
                 if (last == null)
                    consumed = true;
                 else
                    geodesic = last;

                 // Update maxCost
                 if (queue.isFull())
                 {
                    last = newLast;
                    maxCost = newLast.cost;
                 }
            }

            // All requested geodesics have been found with a cost of 0 => done !
            if ((maxCost == 0) && (queue.isFull() == true))
                break;
        }

        return queue.toArray(new Geodesic[queue.size()]);
    }


    private static void copy(int[] src, int srcIdx, int[] dst, int dstIdx, int len, int inc1)
    {
        final int len4 = len & -4;
        final int inc2 = inc1 + inc1;
        final int inc3 = inc2 + inc1;
        final int inc4 = inc3 + inc1;

        for (int i=0; i<len4; i+=4)
        {
           dst[dstIdx]      = src[srcIdx];
           dst[dstIdx+inc1] = src[srcIdx+inc1];
           dst[dstIdx+inc2] = src[srcIdx+inc2];
           dst[dstIdx+inc3] = src[srcIdx+inc3];
           dstIdx += inc4;
           srcIdx += inc4;
        }

        for (int i=len4; i<len; i++)
        {
           dst[dstIdx] = src[srcIdx];
           dstIdx += inc1;
           srcIdx += inc1;
        }
    }

    
    private int[] calculateCosts(int[] src, int[] costs_)
    {
        // For packed RGB images, use 3 channels mode for more accurate results and 
        // one channel mode (blue) for faster results.  
        // For unpacked images, use one channel mode (Y for YUV or any for RGB).
        SobelFilter gradientFilter = new SobelFilter(this.width, this.height,
                this.offset, this.stride, SobelFilter.HORIZONTAL | SobelFilter.VERTICAL,
                this.sobelMode, SobelFilter.COST);
        gradientFilter.apply(src, costs_);
        
        // Add a quadratic contribution to the cost
        // Favor straight lines if costs of neighbors are all low
        for (int i=0; i<costs_.length; i++)
        {
           final int c = costs_[i];           
           costs_[i] = (c < 5) ? 0 :  c + ((c * c) >> 8); 
        }
        
        return costs_;
    }

}

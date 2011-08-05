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

// Works on square images post wavelet transform
// Uses oriented raster scan
// LL HL (low)      (horizontal)
// LH HH (vertical) (diagonal)
public class WaveletBandIterator
{
    public static final int HL_BAND = 1;
    public static final int LH_BAND = 2;
    public static final int HH_BAND = 4;
    public static final int ALL_BANDS = HL_BAND | HH_BAND | LH_BAND;
    
    private final int dimLLBand;
    private final int dimMax;  // may be less than dimImage
    private final int dimImage;
    private final int bandType;
    private final int size;
    private final int endIndex;
    private int index;
    
    
    public WaveletBandIterator(int dimLLBand, int dimImage, int bandType)
    {
       this(dimLLBand, dimImage, bandType, 31-Integer.numberOfLeadingZeros(dimImage/dimLLBand));
    }
    
    
    // levels is used to limit the scanning to a subset of all bands
    public WaveletBandIterator(int dimLLBand, int dimImage, int bandType, int levels)
    {
        if (dimLLBand < 2)
            throw new IllegalArgumentException("Invalid dimLLBand parameter (must be at least 2)");
        
        if (dimImage < 8)
            throw new IllegalArgumentException("Invalid dimImage parameter (must be at least 8)");
        
        if ((dimImage & (dimImage-1)) != 0)
            throw new IllegalArgumentException("Invalid dimImage parameter (must be a power of 2)");
        
        if (((bandType & HL_BAND) == 0) && ((bandType & LH_BAND) == 0) && ((bandType & HH_BAND) == 0))
            throw new IllegalArgumentException("Invalid bandType parameter");
        
        if (levels < 1)
            throw new IllegalArgumentException("Invalid levels parameter (must be at least 1)");

        this.dimLLBand = dimLLBand;
        this.dimImage = dimImage;
        this.bandType = bandType;
        int sz = 0;
        int subtreeSize = 0;
        int dim = this.dimLLBand;
        
        for (int i=1; i<levels; i++)
           dim <<= 1;

        this.dimMax = dim;
        
        for (int i=this.dimLLBand; i<=this.dimMax; i<<=1)
            subtreeSize += (i * i);
        
        if ((this.bandType & HL_BAND) != 0)
            sz += subtreeSize;
        
        if ((this.bandType & LH_BAND) != 0)
            sz += subtreeSize;
        
        if ((this.bandType & HH_BAND) != 0)
            sz += subtreeSize;
        
        this.size = sz;
        this.endIndex = this.size - (this.dimLLBand * this.dimLLBand);
    }
    
    
    public void reset()
    {
        this.index = 0;
    }
    
    
    public boolean hasNext()
    {
        return (this.index < this.endIndex);
    }
    
    
    // Read a chunk of the subtree of size length
    // Allows the use of an array much smaller than the tree
    // Return the number of integers put in the provided array
    public int getNextIndexes(int[] block, int length)
    {
        int initialDim = this.dimLLBand;
        int count = 0;
        int previousCount = 0;
        int offsetInBand = 0;
        
        if (this.index > 0)
        {
            // Find initial count
            for ( ; initialDim<=this.dimMax; initialDim<<=1)
            {
                if ((this.bandType & HL_BAND) != 0)
                    count += (initialDim * initialDim);
                
                if ((this.bandType & HH_BAND) != 0)
                    count += (initialDim * initialDim);
                
                if ((this.bandType & LH_BAND) != 0)
                    count += (initialDim * initialDim);
                
                if (count > this.index)
                    break;
                
                previousCount = count;
            }
            
            offsetInBand = this.index - previousCount;
            count = 0;
        }
        
        for (int dim=initialDim; dim<=this.dimMax; dim<<=1)
        {
            // Scan sub-band by sub-band with increasing dimension
            count += this.getNextIndexes_(block, length, dim, count, offsetInBand);
            offsetInBand = 0;
            
            if (count == length)
                break;
        }
        
        return count;
    }
    
    
    // Read chunk of band of dimension 'dim' filtered by band type
    // Return the number of integers put in the provided array
    protected int getNextIndexes_(int[] block, int length, int dim, int startBuf,
            int offsetInBand)
    {
        if (dim > this.dimMax)
            return 0;
        
        int mult = dim * this.dimImage;
        int dim2 = dim * dim;
        int remaining = length - startBuf;
        int idxBuf = startBuf;
        int idxSubBand = 0;
        int read = 0;
        
        // HL band: horizontal scan
        if (((this.bandType & HL_BAND) != 0) && (remaining > 0))
        {
            idxSubBand += dim2;
            
            // If already processed in previous call, skip
            if (offsetInBand < idxSubBand)
            {
                int end = dim + mult;
                int endStep = dim + dim;
                int i;
                
                for (int offs=dim; ((offs<end) && (remaining>0)); offs+=this.dimImage)
                {
                    // Find first index to add data to the output
                    for (i=offs; ((i<endStep) && (read<offsetInBand)); i++)
                        read++;
                    
                    for ( ; ((i<endStep) && (remaining>0)); i++, read++, idxBuf++)
                    {
                        block[idxBuf] = i;
                        remaining--;
                    }
                    
                    endStep += this.dimImage;
                }
            }
            else read += dim2;
        }
                
        // LH band: vertical scan
        if (((this.bandType & LH_BAND) != 0) && (remaining > 0))
        {
            idxSubBand += dim2;
            
            if (offsetInBand < idxSubBand)
            {
                int end = dim + mult;
                int endStep = mult + mult;
                int i;
                
                for (int offs=mult; ((offs<end) && (remaining>0)); offs++, endStep++)
                {
                    // Find first index to add data to the output
                    for (i=offs; ((i<endStep) && (read<offsetInBand)); i+=this.dimImage)
                        read++;
                    
                    for ( ; ((i<endStep) && (remaining>0)); i+=this.dimImage, read++, idxBuf++)
                    {
                        block[idxBuf] = i;
                        remaining--;
                    }
                }
            }
            else read += dim2;
        }

        // HH band: diagonal scan (from lower left to higher right)
        if (((this.bandType & HH_BAND) != 0) && (remaining > 0))
        {
            idxSubBand += dim2;
            
            if (offsetInBand < idxSubBand)
            {
                int end = dim + mult + mult;
                int endStep = dim + mult + 1;
                int i, j;
                
                for (int offs=dim+mult; ((offs<end) && (remaining>0)); offs+=this.dimImage)
                {
                    // Find first index to add data to the output
                    for (i=offs, j=0; ((i<endStep) && (read<offsetInBand)); i++, j-=this.dimImage)
                        read++;
                    
                    for ( ; ((i<endStep) && (remaining>0)); i++, read++, idxBuf++)
                    {
                        block[idxBuf] = i + j;
                        remaining--;
                        j -= this.dimImage;
                    }
                    
                    endStep += (this.dimImage + 1);
                }
                
                int offset = end - this.dimImage + 1;
                end = offset + dim;
                endStep = offset + dim - 1;
                
                for (int offs=offset; ((offs<end) && (remaining>0)); offs++)
                {
                    // Find first index to add data to the output
                    for (i=offs, j=0; ((i<endStep) && (read<offsetInBand)); i++, j-=this.dimImage)
                        read++;
                    
                    for ( ; ((i<endStep) && (remaining>0)); i++, read++, idxBuf++)
                    {
                        block[idxBuf] = i + j;
                        remaining--;
                        j -= this.dimImage;
                    }
                }
            }
        }
        
        this.index += (idxBuf - startBuf);
        return idxBuf - startBuf;
    }
    
    
    // Read whole tree (except top LL band) filtered by band type
    // Max speed compared to partial scan
    // Return the number of integers put in the provided array
    public int getIndexes(int[] block)
    {
        int count = 0;
        
        for (int dim=this.dimLLBand; dim<=this.dimMax; dim<<=1)
        {
            // Scan sub-band by sub-band with increasing dimension
            count += this.getIndexes_(block, dim, count);
        }
        
        return count;
    }
    
    
    // Read band of dimension 'dim' filtered by band type
    // Return the number of integers put in the provided array
    public int getBandIndexes(int[] block, int dim)
    {
        return this.getIndexes_(block, dim, 0);
    }
    
    
    protected int getIndexes_(int[] block, int dim, int start)
    {
        if (dim > this.dimMax)
            return 0;
        
        int idx = start;
        int mult = dim * this.dimImage;
        
        // HL band: horizontal scan
        if ((this.bandType & HL_BAND) != 0)
        {
            int end = dim + mult;
            int endStep = dim + dim;
            
            for (int offs=dim; offs<end; offs+=this.dimImage)
            {
                for (int i=offs; i<endStep; i++, idx++)
                    block[idx] = i;
                
                endStep += this.dimImage;
            }
        }
                
        // LH band: vertical scan
        if ((this.bandType & LH_BAND) != 0)
        {
            int end = dim + mult;
            int endStep = mult + mult;
            
            for (int offs=mult; offs<end; offs++)
            {
                for (int i=offs; i<endStep; i+=this.dimImage, idx++)
                    block[idx] = i;
                
                endStep++;
            }
        }

        // HH band: diagonal scan (from lower left to higher right)
        if ((this.bandType & HH_BAND) != 0)
        {
            int end = dim + mult + mult;
            int endStep = dim + mult + 1;
            
            for (int offs=dim+mult; offs<end; offs+=this.dimImage)
            {
                for (int i=offs, j=0; i<endStep; i++, idx++)
                {
                    block[idx] = i + j;
                    j -= this.dimImage;
                }
                
                endStep += (this.dimImage + 1);
            }
                        
            int offset = end - this.dimImage + 1;
            end = offset + dim;
            endStep = offset + dim - 1;
            
            for (int offs=offset; offs<end; offs++)
            {
                for (int i=offs, j=0; i<endStep; i++, idx++)
                {
                    block[idx] = i + j;
                    j -= this.dimImage;
                }
            }
        }
        
        return idx - start;
    }
    
}

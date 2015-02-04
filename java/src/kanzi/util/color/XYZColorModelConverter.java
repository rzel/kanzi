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

package kanzi.util.color;

import kanzi.ColorModelType;


// Fat color model converter RGB <-> CIE 1931 XYZ
public final class XYZColorModelConverter implements ColorModelConverter
{
    private final int height;
    private final int width;
    private final int rgbOffset;
    private final int stride;


    public XYZColorModelConverter(int width, int height)
    {
        this(width, height, 0, width);
    }


    // rgbOffset is the offset in the RGB frame while stride is the width of the RGB frame
    // width and height are the dimension of the XYZ frame
    public XYZColorModelConverter(int width, int height, int rgbOffset, int stride)
    {
        if (height < 8)
            throw new IllegalArgumentException("The height must be at least 8");

        if (width < 8)
            throw new IllegalArgumentException("The width must be at least 8");

        if (stride < 8)
            throw new IllegalArgumentException("The stride must be at least 8");

        if ((height & 7) != 0)
            throw new IllegalArgumentException("The height must be a multiple of 8");

        if ((width & 7) != 0)
            throw new IllegalArgumentException("The width must be a multiple of 8");

        if ((stride & 7) != 0)
            throw new IllegalArgumentException("The stride must be a multiple of 8");

        this.height = height;
        this.width = width;
        this.rgbOffset = rgbOffset;
        this.stride = stride;
    }


    // conversion matrix (XYZ range may exceed 255 if RGB in [0..255])
    // 0.4124564  0.3575761  0.1804375
    // 0.2126729  0.7151522  0.0721750
    // 0.0193339  0.1191920  0.9503041
    @Override
    public boolean convertRGBtoYUV(int[] rgb, int[] x, int[] y, int[] z, ColorModelType type)
    {
        if (type != ColorModelType.XYZ)
           return false;
       
        int startLine  = this.rgbOffset;
        int startLine2 = 0;

        for (int j=0; j<this.height; j++)
        {
            int end = startLine + this.width;

            for (int k=startLine, i=startLine2; k<end; i++)
            {
                // ------- fromRGB 'Macro'
                final int rgbVal = rgb[k++];
                final int r = (rgbVal >> 16) & 0xFF;
                final int g = (rgbVal >> 8)  & 0xFF;
                final int b =  rgbVal & 0xFF;
                
                x[i] = (6758*r + 5859*g  +  2956*b + 8192) >> 14;
                y[i] = (3484*r + 11717*g +  1183*b + 8192) >> 14;
                z[i] = ( 317*r +  1953*g + 15570*b + 8192) >> 14;
                // ------- fromRGB 'Macro'  END
            }

            startLine2 += this.width;
            startLine  += this.stride;
        }

        return true;
    }


    // conversion matrix 
    //  3.2404542 -1.5371385 -0.4985314
    // -0.9692660  1.8760108  0.0415560
    //  0.0556434 -0.2040259  1.0572252
    @Override
    public boolean convertYUVtoRGB(int[] x, int[] y, int[] z, int[] rgb, ColorModelType type)
    {
        if (type != ColorModelType.XYZ)
           return false;

        int startLine = 0;
        int startLine2 = this.rgbOffset;

        for (int j=0; j<this.height; j++)
        {
            int end = startLine + this.width;

            for (int i=startLine, k=startLine2; i<end; i++)
            {
                // ------- toRGB 'Macro'
                final int xVal = x[i];
                final int yVal = y[i];
                final int zVal = z[i];

                int r =  (53091*xVal - 25184*yVal -  8168*zVal + 8192) >> 14;
                int g = (-15880*xVal + 30737*yVal +   681*zVal + 8192) >> 14;
                int b =  (  912*xVal -  3343*yVal + 17322*zVal + 8192) >> 14;
                
                // clip (0, 255)
                r = ((~(r >> 31)) & 255 & (r | ((255-r) >> 31))); 
                g = ((~(g >> 31)) & 255 & (g | ((255-g) >> 31))); 
                b = ((~(b >> 31)) & 255 & (b | ((255-b) >> 31))); 
                // ------- toRGB 'Macro' END

                rgb[k++] = (r << 16) | (g << 8) | b;
            }

            startLine  += this.width;
            startLine2 += this.stride;
        }

        return true;
    }
}

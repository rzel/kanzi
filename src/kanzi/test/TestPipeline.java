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


package kanzi.test;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.BitStream;
import kanzi.ColorModelType;
import kanzi.IndexedByteArray;
import kanzi.IndexedIntArray;
import kanzi.bitstream.DefaultBitStream;
import kanzi.EntropyDecoder;
import kanzi.EntropyEncoder;
import kanzi.entropy.RangeDecoder;
import kanzi.entropy.RangeEncoder;
import kanzi.function.wavelet.WaveletBandFilter;
import kanzi.transform.DWT_CDF_9_7;
import kanzi.function.BlockCodec;
import kanzi.util.color.YSbSrColorModelConverter;
import kanzi.util.ImageQualityMonitor;


public class TestPipeline
{
   static class Entry
   {
      Entry prev;
      Integer number;

      Entry (Entry e, int n)
      {
         prev = e;
         number = n;
      }
   }

    public static void main(String[] args)
    {
        try
        {
            String fileName = "C:\\temp\\lena.jpg";
            ImageIcon icon = new ImageIcon(fileName);
            Image image = icon.getImage();
            int w = image.getWidth(null);
            int h = image.getHeight(null);
            System.out.println(w + "x" + h);


            if (w != h)
            {
                System.err.println("Width and height must be equal");
                w = h;
                System.err.println("Resizing to "+w+"x"+h);
                //System.exit(1);
            }

            GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
            GraphicsConfiguration gc = gs.getDefaultConfiguration();
            BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);

            img.getGraphics().drawImage(image, 0, 0, null);
            int[] rgb = new int[w*h];
            int[] rgb2 = new int[w*h];
            int[] u = new int[rgb.length];
            int[] y = new int[rgb.length];
            int[] v = new int[rgb.length];

            // Do NOT use img.getRGB(): it is more than 10 times slower than
            // img.getRaster().getDataElements()
            img.getRaster().getDataElements(0, 0, w, h, rgb);

            encode(fileName, rgb, y, u, v, w, h);
//System.exit(0);
            decode(fileName, rgb2, y, u, v, w, h);

            int psnr1024 = new ImageQualityMonitor(w, h).computePSNR(rgb, rgb2);
            System.out.println("PSNR: "+(float) psnr1024 / 1024);

            // Do NOT use img.setRGB(): it is more than 10 times slower than
            // img.getRaster().setDataElements()
            //img.getRaster().setDataElements(0, 0, w, h, rgb);
            BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img2.getRaster().setDataElements(0, 0, w, h, rgb2);
            icon = new ImageIcon(img);

            JFrame frame = new JFrame("Before");
            frame.setBounds(50, 30, w, h);
            frame.add(new JLabel(icon));
            frame.setVisible(true);
            JFrame frame2 = new JFrame("After");
            frame2.setBounds(600, 30, w, h);
            ImageIcon newIcon = new ImageIcon(img2);
            frame2.add(new JLabel(newIcon));
            frame2.setVisible(true);

            try
            {
                Thread.sleep(35000);
            }
            catch (Exception e)
            {
            }

           System.exit(0);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }


    private static void encode(String fileName, int[] rgb, int[] y, int[] u, int[] v, int w, int h) throws Exception
    {
        File output = new File(fileName.substring(0, fileName.length()-3).concat("bin"));
        FileOutputStream fos = new FileOutputStream(output);
        BitStream dbs = new DefaultBitStream(fos, 16384);

        int dimImage = h;
        DWT_CDF_9_7 ydwt = new DWT_CDF_9_7(dimImage);
        DWT_CDF_9_7 uvdwt = new DWT_CDF_9_7(dimImage/2);
        EntropyEncoder entropyEncoder = new RangeEncoder(dbs);
//        Resampler rs = new BilinearResampler(w, h, 2);
//        DefaultColorModelConverter cvt = new DefaultColorModelConverter(w, h, rs);
        YSbSrColorModelConverter cvt = new YSbSrColorModelConverter(w, h);
        long before = System.nanoTime();
        int iter = 100;

        for (int ii=0; ii<iter; ii++)
        {
            // Color space conversion
            cvt.convertRGBtoYUV(rgb, y, u, v, ColorModelType.YUV420);

            // Discrete Wavelet Transform
            encode_(dbs, y, ydwt, entropyEncoder);
            encode_(dbs, u, uvdwt, entropyEncoder);
            encode_(dbs, v, uvdwt, entropyEncoder);
        }

        entropyEncoder.dispose();
        dbs.close();
        long after = System.nanoTime();
        System.out.println("Encoding time [ms]: "+(after-before)/1000000);
        System.out.println("Read: "+(w*h*3*iter));
        System.out.println("Written: "+(dbs.written() >> 3));
        float r = (float) (w*h*3*iter) / (dbs.written() >> 3);
        System.out.println("Compression ratio: "+ r);
    }


     private static void encode_(BitStream dbs, int[] data, DWT_CDF_9_7 dwt, EntropyEncoder encoder)
     {
        int DIM_BAND_LL = dwt.getDimensionBandLL();
        int levels = 0;
        int dimImage = dwt.getDimension();

        for (int dim=DIM_BAND_LL; dim<dimImage; dim<<=1)
            levels++;

        // Perform Discrete Wavelet Transform band by band
        dwt.forward(data, 0);

        IndexedIntArray source = new IndexedIntArray(data, 0);
        int[] buffer = new int[dimImage*dimImage];
        IndexedIntArray destination = new IndexedIntArray(buffer, 0);

        // Quantization
        int[] quantizers = new int[levels+1];
        quantizers[0] = 310;//169;
        quantizers[1] = 40;//26;

        for (int i=2; i<quantizers.length; i++)
        {
            // Derive quantizer values for higher bands
            quantizers[i] = ((quantizers[i-1]) * 17 + 2) >> 4;
        }

//        WaveletRateDistorsionFilter filter = new WaveletRateDistorsionFilter(
//                dimImage, levels, 100, dimImage, true);
        WaveletBandFilter filter = new WaveletBandFilter(dimImage,
                DIM_BAND_LL, levels, quantizers);

        // The filter guarantees that all coefficients have been shrunk to byte values
        filter.forward(source, destination);

        IndexedByteArray block = new IndexedByteArray(new byte[destination.index], 0);

        for (int i=0; i<destination.index; i++)
           block.array[i] = (byte) destination.array[i];

        // Block encoding
        BlockCodec bc = new BlockCodec();
        int length = destination.index;
        block.index = 0;

        while (length > 0)
        {
            int blkSize = (length < 65535) ? length : 65535;
            bc.setSize(blkSize);
            int encoded = bc.encode(block, encoder);

            if (encoded < 0)
            {
              System.out.println("Error during block encoding");
              System.exit(1);
            }

            length -= blkSize;
        }

        // End of block: add empty block 0 0 0 or 0x80
        encoder.encodeByte((byte) 0); // mode
        encoder.encodeByte((byte) 0); // MSB size
        encoder.encodeByte((byte) 0); // LSB size
    }


    private static void decode(String fileName, int[] rgb, int[] y, int[] u, int[] v, int w, int h) throws Exception
    {
        File input = new File(fileName.substring(0, fileName.length()-3).concat("bin"));
        FileInputStream is = new FileInputStream(input);
        BitStream dbs = new DefaultBitStream(is, 16384);

        int dimImage = h;
        DWT_CDF_9_7 ydwt = new DWT_CDF_9_7(dimImage);
        DWT_CDF_9_7 uvdwt = new DWT_CDF_9_7(dimImage/2);
        EntropyDecoder entropyDecoder = new RangeDecoder(dbs);

        YSbSrColorModelConverter cvt = new YSbSrColorModelConverter(w, h);
        long before = System.nanoTime();
        int iter = 100;

        for (int ii=0; ii<iter; ii++)
        {
            // Discrete Wavelet Inverse Transform
            decode_(dbs, y, ydwt, entropyDecoder);
            decode_(dbs, u, uvdwt, entropyDecoder);
            decode_(dbs, v, uvdwt, entropyDecoder);

            // Color space conversion
            cvt.convertYUVtoRGB(y, u, v, rgb, ColorModelType.YUV420);
        }

        long after = System.nanoTime();
        entropyDecoder.dispose();
        dbs.close();
        System.out.println("Decoding time [ms]: "+(after-before)/1000000);
        System.out.println("Read: "+(dbs.read() >> 3));
    }


    private static void decode_(BitStream dbs, int[] data, DWT_CDF_9_7 dwt, EntropyDecoder decoder)
    {
        // Block decoding
        int dimImage = dwt.getDimension();
        IndexedByteArray buffer = new IndexedByteArray(new byte[dimImage*dimImage], 0);
        BlockCodec bd = new BlockCodec();
        int decoded = 0;

        do
        {
           decoded = bd.decode(buffer, decoder);

           if (decoded < 0)
           {
              System.out.println("Error during block decoding");
              System.exit(1);
           }
        }
        while (decoded > 0);


        int DIM_BAND_LL = dwt.getDimensionBandLL();
        int levels = 0;

        for (int dim=DIM_BAND_LL; dim<dimImage; dim<<=1)
            levels++;

        // Dequantization

        // Quantizers must be known by the filter !!!
        // TODO: transmit quantizers
        int[] quantizers = new int[levels+1];
        quantizers[0] = 310;//169;
        quantizers[1] = 40;//26;

        for (int i=2; i<quantizers.length; i++)
        {
            // Derive quantizer values for higher bands
            quantizers[i] = ((quantizers[i-1]) * 17 + 2) >> 4;
        }

        // Inverse quantization
        WaveletBandFilter filter = new WaveletBandFilter(dimImage,
                DIM_BAND_LL, levels, quantizers);

        IndexedIntArray destination = new IndexedIntArray(data, 0);
        IndexedIntArray source = new IndexedIntArray(new int[dimImage*dimImage], 0);

        for (int i=0; i<buffer.index; i++)
           source.array[i] = (int) buffer.array[i];

        filter.inverse(source, destination);

        // Perform inverse Discrete Wavelet Transform band by band
        dwt.inverse(data, 0);
    }
}

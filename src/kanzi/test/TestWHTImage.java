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
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.ColorModelType;
import kanzi.EntropyEncoder;
import kanzi.Global;
import kanzi.IndexedByteArray;
import kanzi.BitStream;
import kanzi.bitstream.DefaultBitStream;
import kanzi.entropy.RangeEncoder;
import kanzi.function.BlockCodec;
import kanzi.transform.WHT8;
import kanzi.util.color.ColorModelConverter;
import kanzi.util.ImageQualityMonitor;
import kanzi.util.color.YCbCrColorModelConverter;
import kanzi.util.color.YSbSrColorModelConverter;


public class TestWHTImage
{

    public static void main(String[] args)
    {
        String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
        ImageIcon icon = new ImageIcon(fileName);
  //      ImageIcon icon = new ImageIcon("C:\\temp\\big_buck_bunny_09500.png");
        //ImageIcon icon = new ImageIcon("f:\\temp\\daggers\\daggers0500.jpg");
        Image image = icon.getImage();
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
        GraphicsConfiguration gc = gs.getDefaultConfiguration();
        BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);

        img.getGraphics().drawImage(image, 0, 0, null);
        System.out.println(w + "x" + h);
        int[] rgb = new int[w*h];
        int[] rgb2 = new int[w*h];
        int[] data = new int[w*h];
        // Do NOT use img.getRGB(): it is more than 10 times slower than
        // img.getRaster().getDataElements()
        img.getRaster().getDataElements(0, 0, w, h, rgb);
        BlockCodec codec = new BlockCodec(32768);
        byte[] output = new byte[w*h];
        // byte[] tmp = new byte[w*h];

        int[] yy = new int[rgb.length];
        int[] uu = new int[rgb.length/4];
        int[] vv = new int[rgb.length/4];

        ColorModelConverter cvt;
        cvt = new YSbSrColorModelConverter(w, h);
        cvt = new YCbCrColorModelConverter(w, h);
        cvt.convertRGBtoYUV(rgb, yy, uu, vv, ColorModelType.YUV420);

        IndexedByteArray iba1 = new IndexedByteArray(output, 0);
        // IndexedByteArray iba2 = new IndexedByteArray(tmp, 0);
        OutputStream os = new ByteArrayOutputStream(w*h);
        BitStream bs = new DefaultBitStream(os, w*h);
        EntropyEncoder ee = new RangeEncoder(bs);

        int nonZero = 0;
        nonZero += forward(yy,   w,   h, data);
        reverse(data,   w,   h, yy);
        nonZero += forward(uu, w/2, h/2, data);
        reverse(data, w/2, h/2, uu);
        nonZero += forward(vv, w/2, h/2, data);
        reverse(data, w/2, h/2, vv);

        cvt.convertYUVtoRGB(yy, uu, vv, rgb2, ColorModelType.YUV420);

        ee.dispose();
        //System.out.println("Compressed length: "+ee.getBitStream().written() / 8);
        System.out.println("Not null coeffs: "+nonZero+"/"+((w*h)+(w*h/2)));
        System.out.println("PNSR: "+new ImageQualityMonitor(w, h).computePSNR(rgb, rgb2)/1024.0);

        BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
        img2.getRaster().setDataElements(0, 0, w, h, rgb2);
        icon = new ImageIcon(img);

        JFrame frame = new JFrame("Image");
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


    private static int forward(int[] data, int w, int h, int[] output)
    {
        WHT8 wht = new WHT8();
        int[] block = new int[64];
        int nonZero = 0;

        for (int y=0; y<h; y+=8)
        {
           for (int x=0; x<w; x+=8)
           {
              for (int j=0; j<8; j++)
                 System.arraycopy(data, (y+j)*w+x, block, 8*j, 8);

              wht.forward(block);
              System.out.println();

              for (int j=0; j<8; j++)
              {
                  for (int i=0; i<8; i++)
                  {
                     int idx = (j<<3)+i;
                     block[idx] = (block[idx] * Global.QUANTIZATION_INTRA[idx] + 128) >> 8;
                     int val = block[idx];

                     if (val != 0)
                         nonZero++;
                     
                     int abs = Math.abs(val);
                     String s = (val>=0) ? " " : "";
                     s += (abs < 100) ? " " : "";
                     s += (abs < 10) ? " " : "";
                     s += val;
                     System.out.print(s+" ");
                  }

                  System.out.println();
              }

              for (int j=0; j<8; j++)
                 System.arraycopy(block, 8*j, output, (y+j)*w+x, 8);
          }
        }

        return nonZero;
    }



     private static void reverse(int[] data, int w, int h, int[] output)
     {
        WHT8 wht = new WHT8();
        int[] block = new int[64];

        for (int y=0; y<h; y+=8)
        {
           for (int x=0; x<w; x+=8)
           {
              for (int j=0; j<8; j++)
                 System.arraycopy(data, (y+j)*w+x, block, 8*j, 8);

              for (int j=0; j<8; j++)
              {
                  for (int i=0; i<8; i++)
                  {
                     int idx = (j<<3)+i;
                     block[idx] = (block[idx] * Global.DEQUANTIZATION_INTRA[idx]);
                  }
              }

              wht.inverse(block);

              for (int j=0; j<8; j++)
                 System.arraycopy(block, 8*j, output, (y+j)*w+x, 8);
          }
        }
     }
}

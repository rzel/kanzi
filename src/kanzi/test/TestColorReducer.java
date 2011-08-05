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

import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeSet;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.util.color.ColorReducer;
import kanzi.util.ImageQualityMonitor;



public class TestColorReducer
{
  public static void main(String[] args)
  {
    int bits = 8;

    try
    {
      String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
      ImageIcon icon = new ImageIcon(fileName);
      Image image = icon.getImage();
      int w = image.getWidth(null);
      int h = image.getHeight(null);
      Frame frame = new JFrame("Original - RGB 24 bits");
      frame.setBounds(20, 50, w, h);
      GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
      GraphicsConfiguration gc = gs.getDefaultConfiguration();
      BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
      img.getGraphics().drawImage(image, 0, 0, null);
      int[] rgb = new int[w*h];
      img.getRaster().getDataElements(0, 0, w, h, rgb);
      frame.add(new JLabel(icon));
      frame.setVisible(true);
      boolean debug = false;

      System.out.println("Image dimensions: "+w+"*"+h);
      int[] iRGB = rgb;

      if (debug)
      {
         TreeSet<Integer> set = new TreeSet<Integer>();

         // Remove duplicate colors
         for (int i=0; i<rgb.length; i++)
           set.add(rgb[i]);

        System.out.println("Unique colors in original: "+set.size());
        System.out.println("Unique colors in target: "+(1<<bits));
        System.out.println("Initial color values");

        // Display initial color values
        Iterator<Integer> it = set.iterator();
        int nn = 0;

        while (it.hasNext())
        {
          System.out.println(nn+" => "+Integer.toHexString(it.next()));
          nn++;
        }
      }

      System.out.println("Applying filter ...");
      int max = 1 << bits;
      int[] oRGB = new int[max];
      ColorReducer filter = new ColorReducer(24, bits);
      oRGB = filter.apply(iRGB, oRGB);

      if (debug)
      {
         System.out.println("Reduced color values");

         // Display new color values
         Arrays.sort(oRGB);

         for (int i=0; i<oRGB.length; i++)
           System.out.println(i+" => "+Integer.toHexString(oRGB[i]));
       }

       byte[] r = new byte[max];
       byte[] g = new byte[max];
       byte[] b = new byte[max];

       for (int i=0; i<max; i++)
       {
         r[i] = (byte) ((oRGB[i] >> 16) & 0xFF);
         g[i] = (byte) ((oRGB[i] >> 8)  & 0xFF);
         b[i] = (byte) ( oRGB[i]        & 0xFF);
       }

       IndexColorModel cm = new IndexColorModel(bits, max, r, g, b);
       BufferedImage img2 = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
       img2.getGraphics().drawImage(img, 0, 0, null);
       ImageIcon icon2 = new ImageIcon(img2);
       Frame frame2 = new JFrame("Reduced colors - "+bits+" bits");
       frame2.setBounds(870, 50, w, h);
       frame2.add(new JLabel(icon2));
       frame2.setVisible(true);

       oRGB = filter.apply(iRGB, oRGB);
       ImageQualityMonitor iqm = new ImageQualityMonitor(w, h);
       IndexColorModel cm2 = new IndexColorModel(bits, max, r, g, b);
       BufferedImage img3 = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm2);
       img3.getGraphics().drawImage(img, 0, 0, null);
       BufferedImage img4 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
       img4.getGraphics().drawImage(img3, 0, 0, null);
       oRGB = new int[w*h];
       img4.getRaster().getDataElements(0, 0, w, h, oRGB);
       int psnr1024 = iqm.computePSNR(iRGB, oRGB);
       int ssim1024 = iqm.computeSSIM(iRGB, oRGB);
       System.out.println("PSNR: "+((float) psnr1024/1024.0)+" - SSIM: "+((float) ssim1024/1024.0));
       ImageIcon icon3 = new ImageIcon(img3);
       Frame frame3 = new JFrame("Reduced colors - downsampled by 2 - "+bits+" bits");
       frame3.setBounds(470, 350, w, h);
       frame3.add(new JLabel(icon3));
       frame3.setVisible(true);

       System.out.println("Speed test");
       int iter = 100;

       System.out.println("Original size");
       long before = System.nanoTime();

       for (int ii=0; ii<iter; ii++)
           filter.apply(iRGB, oRGB);

       long after = System.nanoTime();
       System.out.println("Elapsed [ms]: "+iter+" iterations): "+(after-before)/1000000);

       System.out.println("Downsampled by 2");
       filter = new ColorReducer(24, bits, 2);
       before = System.nanoTime();

       for (int ii=0; ii<iter; ii++)
           filter.apply(iRGB, oRGB);

       after = System.nanoTime();
       System.out.println("Elapsed [ms]: "+iter+" iterations): "+(after-before)/1000000);

       Thread.sleep(40000);
       System.exit(0);
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
   }

}
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
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.filter.ColorClusterFilter;
import kanzi.filter.FastBilateralFilter;
import kanzi.filter.SobelFilter;


public class TestColorClusterFilter
{
    public static void main(String[] args)
    {
        try
        {
            String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
            ImageIcon icon = new ImageIcon(fileName);
            Image image = icon.getImage();
            int w = image.getWidth(null) & -7;
            int h = image.getHeight(null) & -7;
            System.out.println(w+"x"+h);
            GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
            GraphicsConfiguration gc = gs.getDefaultConfiguration();
            BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img.getGraphics().drawImage(image, 0, 0, null);
            BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            int[] source = new int[w*h];
            int[] temp = new int[w*h];
            int[] dest = new int[w*h];
            boolean applySobel = true;
            boolean applyBilateral = true;

            // Do NOT use img.getRGB(): it is more than 10 times slower than
            // img.getRaster().getDataElements()
            img.getRaster().getDataElements(0, 0, w, h, source);

            ColorClusterFilter effect = new ColorClusterFilter(w, h, 40, 12);
            //System.arraycopy(dest, 0, source, 0, w*h);
            effect.apply(source, dest);

            if (applySobel == true)
            {
               // Apply Sobel filter
               SobelFilter sb = new SobelFilter(w, h);
               sb.apply(dest, temp);

               for (int i=0; i<w*h; i++)
               {
                  int pix = temp[i] & 0xFF;

                  // Add a line
                  if (pix < 0x40)
                     continue;

                  pix >>= 1;
                  int r = (dest[i] >> 16) & 0xFF;
                  int g = (dest[i] >>  8) & 0xFF;
                  int b =  dest[i] & 0xFF;

                  r += pix;
                  g += pix;
                  b += pix;

                  if (r > 255)
                     r = 255;
                  if (g > 255)
                     g = 255;
                  if (b > 255)
                     b = 255;

                 dest[i] = (r<<16) | (g<<8) | b;
               }
            }

            // Smooth the results by adding bilateral filtering
            if (applyBilateral == true)
            {
               FastBilateralFilter fbl = new FastBilateralFilter(w, h, 40.0f, 0.03f);
               fbl.apply(dest, dest);            
            }

            img2.getRaster().setDataElements(0, 0, w, h, dest);

            //icon = new ImageIcon(img);
            JFrame frame = new JFrame("Original");
            frame.setBounds(150, 100, w, h);
            frame.add(new JLabel(icon));
            frame.setVisible(true);
            JFrame frame2 = new JFrame("Filter");
            frame2.setBounds(700, 150, w, h);
            ImageIcon newIcon = new ImageIcon(img2);
            frame2.add(new JLabel(newIcon));
            frame2.setVisible(true);

            // Speed test
            {
                int[] tmp = new int[w*h];
                System.arraycopy(source, 0, tmp, 0, w * h);
                System.out.println("Speed test");
                int iters = 1000;
                long before = System.nanoTime();

                for (int ii=0; ii<iters; ii++)
                {
                   effect.apply(source, tmp);
                }

                long after = System.nanoTime();
                System.out.println("Elapsed [ms]: "+ (after-before)/1000000+" ("+iters+" iterations)");
            }

            try
            {
                Thread.sleep(45000);
            }
            catch (Exception e)
            {
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        System.exit(0);
    }
}


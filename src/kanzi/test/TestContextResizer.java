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
import java.util.Arrays;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import kanzi.filter.seam.ContextResizer;


public class TestContextResizer
{
    public static void main(String[] args)
    {
        try
        {
            String fileName = (args.length > 0) ? args[0] : "c:\\temp\\lena.jpg";
            ImageIcon icon = new ImageIcon(fileName);
            Image image = icon.getImage();
            int w = image.getWidth(null);
            int h = image.getHeight(null);
            System.out.println(w+"x"+h);
            GraphicsDevice gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0];
            GraphicsConfiguration gc = gs.getDefaultConfiguration();
            BufferedImage img = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img.getGraphics().drawImage(image, 0, 0, null);
            JFrame frame = new JFrame("Original");
            frame.setBounds(50, 50, w, h);
            frame.add(new JLabel(icon));
            frame.setVisible(true);
            int[] src = new int[w*h];
            int[] dst = new int[w*h];

            // Do NOT use img.getRGB(): it is more than 10 times slower than
            // img.getRaster().getDataElements()
            img.getRaster().getDataElements(0, 0, w, h, src);
            ContextResizer effect;


            // Vertical Filter
            Arrays.fill(dst, 0);
            int dir = ContextResizer.VERTICAL;
            int diff = (dir == ContextResizer.VERTICAL) ? (w * 8) / 100 : (h * 8) / 100;
            effect = new ContextResizer(w, h, 0, w, dir,
                    ContextResizer.SHRINK,
                    (dir == ContextResizer.VERTICAL) ? w : h, diff);//(w*10)/100);
            effect.apply(src, dst);

            BufferedImage img2 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img2.getRaster().setDataElements(0, 0, w, h, dst);
            JFrame frame2 = new JFrame("Filter - Vertical");
            frame2.setBounds(500, 80, w, h);
            ImageIcon icon2 = new ImageIcon(img2);
            frame2.add(new JLabel(icon2));
            frame2.setVisible(true);


            // Vertical Filter with debug
            Arrays.fill(dst, 0);
            img.getRaster().getDataElements(0, 0, w, h, src);
            effect = new ContextResizer(w, h, 0, w, dir,
                    ContextResizer.SHRINK,
                    (dir == ContextResizer.VERTICAL) ? w : h, diff);//(w*10)/100);
            effect.setDebug(true);
            effect.apply(src, dst);
            BufferedImage img3 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img3.getRaster().setDataElements(0, 0, w, h, dst);
            JFrame frame3 = new JFrame("Filter - Vertical - Debug");
            frame3.setBounds(950, 110, w, h);
            ImageIcon icon3 = new ImageIcon(img3);
            frame3.add(new JLabel(icon3));
            frame3.setVisible(true);


            // Horizontal Filter
            Arrays.fill(dst, 0);
            img.getRaster().getDataElements(0, 0, w, h, src);
            dir = ContextResizer.HORIZONTAL;
            diff = (dir == ContextResizer.VERTICAL) ? (w * 8) / 100 : (h * 8) / 100;
            effect = new ContextResizer(w, h, 0, w, dir,
                    ContextResizer.SHRINK,
                    (dir == ContextResizer.VERTICAL) ? w : h, diff);//(w*10)/100);
            effect.apply(src, dst);

            BufferedImage img4 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img4.getRaster().setDataElements(0, 0, w, h, dst);
            JFrame frame4 = new JFrame("Filter - Horizontal");
            frame4.setBounds(500, 480, w, h);
            ImageIcon icon4 = new ImageIcon(img4);
            frame4.add(new JLabel(icon4));
            frame4.setVisible(true);


            // Horizontal Filter with debug
            Arrays.fill(dst, 0);
            img.getRaster().getDataElements(0, 0, w, h, src);
            effect = new ContextResizer(w, h, 0, w, dir,
                    ContextResizer.SHRINK,
                    (dir == ContextResizer.VERTICAL) ? w : h, diff);//(w*10)/100);
            effect.setDebug(true);
            effect.apply(src, dst);
            BufferedImage img5 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img5.getRaster().setDataElements(0, 0, w, h, dst);
            JFrame frame5 = new JFrame("Filter - Horizontal - Debug");
            frame5.setBounds(950, 500, w, h);
            ImageIcon icon5 = new ImageIcon(img5);
            frame5.add(new JLabel(icon5));
            frame5.setVisible(true);


            // Vertical and Horizontal Filter
            Arrays.fill(dst, 0);
            dir = ContextResizer.VERTICAL | ContextResizer.HORIZONTAL;
            diff = (dir == ContextResizer.VERTICAL) ? (w * 8) / 100 : (h * 8) / 100;
            effect = new ContextResizer(w, h, 0, w, dir,
                    ContextResizer.SHRINK,
                    (dir == ContextResizer.VERTICAL) ? w : h, diff);//(w*10)/100);
            effect.apply(src, dst);
            BufferedImage img6 = gc.createCompatibleImage(w, h, Transparency.OPAQUE);
            img6.getRaster().setDataElements(0, 0, w, effect.getHeight(), dst);
            JFrame frame6 = new JFrame("Filter - Vertical & Horizontal");
            frame6.setBounds(50, 500, w, h);
            ImageIcon icon6 = new ImageIcon(img6);
            frame6.add(new JLabel(icon6));
            frame6.setVisible(true);


            // Speed test
            {
                System.out.println("Speed test");
                long before = System.nanoTime();
                long sum = 0;
                int iter = 1000;

                for (int ii=0; ii<iter; ii++)
                {
                   effect = new ContextResizer(w, h, 0, w, ContextResizer.VERTICAL,
                        ContextResizer.SHRINK, w, (w*5)/100);

                   effect.apply(src, dst);
                }

                long after = System.nanoTime();
                sum += (after - before);
                System.out.println("Elapsed [ms]: "+ sum/1000000+" ("+iter+" iterations)");
            }

            Thread.sleep(40000);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        System.exit(0);
    }
}

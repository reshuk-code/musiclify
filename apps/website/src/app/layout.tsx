import type { Metadata } from "next";
import { Inter, Space_Grotesk } from "next/font/google";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });
const grotesk = Space_Grotesk({ subsets: ["latin"], variable: "--font-grotesk" });

export const metadata: Metadata = {
  title: "Musiclify: every song you love, on your phone and PC",
  description:
    "A free, open-source music player for Windows and Android. Search any song, sing along with synced lyrics, and keep an encrypted offline library on your own devices. No account, no ads, no tracking.",
  openGraph: {
    title: "Musiclify",
    description: "Every song you love. On your phone and PC. Yours. Free and open source.",
    images: ["/screens/desktop-nowplaying.png"],
  },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${inter.variable} ${grotesk.variable} h-full antialiased`}>
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}

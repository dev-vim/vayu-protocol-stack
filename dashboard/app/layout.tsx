import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Vayu Protocol Dashboard",
  description: "Network activity for the Vayu DePIN air quality protocol",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="bg-zinc-950 text-zinc-100 antialiased min-h-screen">
        {children}
      </body>
    </html>
  );
}

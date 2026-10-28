import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Sentinel",
  description: "Observability & Incident Management Platform",
  icons: {
    icon: "/sentinel-icon-v2.ico",
    shortcut: "/sentinel-icon-v2.ico",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="en"
      className="dark"
    >
      <body className="min-h-full">
        {children}
      </body>
    </html>
  );
}
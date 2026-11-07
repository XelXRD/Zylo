import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "Zylo", description: "One interface. Your entire media world." };

// Apply the saved theme before first paint so the page never flashes the wrong colours.
const themeScript = `try{var t=localStorage.getItem("zylo-theme");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en-GB" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body className="min-h-dvh bg-bg font-sans text-ink">{children}</body>
    </html>
  );
}

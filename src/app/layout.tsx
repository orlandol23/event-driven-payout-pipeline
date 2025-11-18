import type { Metadata } from 'next';
import Header from '@/components/layout/Header';
import Footer from '@/components/layout/Footer';
import '@/styles/globals.css';

export const metadata: Metadata = {
  title: {
    default: 'Orlando Fernandes - Senior Software Engineer',
    template: '%s | Orlando Fernandes',
  },
  description:
    'Portfolio profissional de Orlando Fernandes. Senior Software Engineer especializado em React, Next.js e TypeScript.',
  keywords: [
    'React',
    'Next.js',
    'TypeScript',
    'Frontend',
    'Full Stack',
    'Software Engineer',
    'Web Development',
  ],
  authors: [{ name: 'Orlando Fernandes' }],
  creator: 'Orlando Fernandes',
  openGraph: {
    type: 'website',
    locale: 'pt_BR',
    siteName: 'Orlando Fernandes Portfolio',
    title: 'Orlando Fernandes - Senior Software Engineer',
    description:
      'Portfolio profissional de Orlando Fernandes. Senior Software Engineer especializado em React, Next.js e TypeScript.',
  },
  robots: {
    index: true,
    follow: true,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="pt-BR">
      <body className="font-sans flex flex-col min-h-screen antialiased">
        <Header />
        <main className="flex-grow">{children}</main>
        <Footer />
      </body>
    </html>
  );
}

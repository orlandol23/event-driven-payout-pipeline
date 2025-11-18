// Hero section para página inicial
import Link from 'next/link';
import Button from '@/components/ui/Button';

interface HeroProps {
  name: string;
  title: string;
  subtitle: string;
}

export default function Hero({ name, title, subtitle }: HeroProps) {
  return (
    <section className="bg-gradient-to-b from-blue-50 to-white py-20 md:py-32">
      <div className="container mx-auto px-4">
        <div className="max-w-4xl mx-auto text-center">
          <h1 className="text-4xl md:text-6xl font-bold text-gray-900 mb-4">
            Olá, eu sou <span className="text-blue-600">{name}</span>
          </h1>
          <h2 className="text-2xl md:text-3xl text-gray-700 mb-4">{title}</h2>
          <p className="text-lg md:text-xl text-gray-600 mb-8">{subtitle}</p>

          <div className="flex flex-col sm:flex-row gap-4 justify-center">
            <Link href="/projects">
              <Button size="lg">Ver Projetos</Button>
            </Link>
            <Link href="/contact">
              <Button size="lg" variant="outline">
                Entre em Contato
              </Button>
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}

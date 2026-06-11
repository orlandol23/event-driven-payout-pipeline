// Featured Projects section para página inicial
import Link from 'next/link';
import Card from '@/components/ui/Card';
import Button from '@/components/ui/Button';

export default function FeaturedProjects() {
  const featuredProjects = [
    {
      id: '1',
      title: 'E-commerce Platform',
      description:
        'Plataforma de e-commerce completa com Next.js, TypeScript, Stripe e PostgreSQL.',
      tags: ['Next.js', 'TypeScript', 'Stripe', 'PostgreSQL'],
      href: '/projects/ecommerce',
    },
    {
      id: '2',
      title: 'Task Management App',
      description:
        'Aplicação de gerenciamento de tarefas com drag-and-drop, autenticação e real-time updates.',
      tags: ['React', 'Node.js', 'WebSockets', 'MongoDB'],
      href: '/projects/task-manager',
    },
    {
      id: '3',
      title: 'Blog Platform',
      description: 'Plataforma de blog com CMS headless, markdown support e otimização de SEO.',
      tags: ['Next.js', 'Contentful', 'MDX', 'SEO'],
      href: '/projects/blog',
    },
  ];

  return (
    <section className="py-16 bg-gray-50">
      <div className="container mx-auto px-4">
        <h2 className="text-3xl md:text-4xl font-bold text-center text-gray-900 mb-4">
          Projetos em Destaque
        </h2>
        <p className="text-center text-gray-600 mb-12 max-w-2xl mx-auto">
          Alguns dos projetos que desenvolvi demonstrando minhas habilidades técnicas e boas
          práticas de desenvolvimento.
        </p>

        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-8 mb-8">
          {featuredProjects.map(project => (
            <Card
              key={project.id}
              title={project.title}
              description={project.description}
              tags={project.tags}
              href={project.href}
            />
          ))}
        </div>

        <div className="text-center">
          <Link href="/projects">
            <Button size="lg" variant="outline">
              Ver Todos os Projetos
            </Button>
          </Link>
        </div>
      </div>
    </section>
  );
}

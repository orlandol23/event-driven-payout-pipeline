// Project detail page - Static Site Generation + ISR
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import Link from 'next/link';
import Button from '@/components/ui/Button';
import { getProjects, getProjectBySlug } from '@/lib/api';

interface Props {
  params: Promise<{ slug: string }>;
}

// Generate static params no build time
export async function generateStaticParams() {
  const projects = await getProjects();

  return projects.map(project => ({
    slug: project.slug,
  }));
}

// Generate metadata
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params;
  const project = await getProjectBySlug(slug);

  if (!project) {
    return {
      title: 'Projeto não encontrado',
    };
  }

  return {
    title: project.name,
    description: project.description,
  };
}

// ISR: revalida a cada 1 hora
export const revalidate = 3600;

export default async function ProjectPage({ params }: Props) {
  const { slug } = await params;
  const project = await getProjectBySlug(slug);

  if (!project) {
    notFound();
  }

  return (
    <article className="container mx-auto px-4 py-12">
      <div className="max-w-4xl mx-auto">
        {/* Breadcrumb */}
        <nav className="mb-8">
          <Link href="/projects" className="text-blue-600 hover:underline">
            ← Voltar para Projetos
          </Link>
        </nav>

        {/* Header */}
        <header className="mb-8">
          <h1 className="text-4xl md:text-5xl font-bold text-gray-900 mb-4">{project.name}</h1>
          <p className="text-xl text-gray-600 mb-6">{project.description}</p>

          {/* Tags */}
          <div className="flex flex-wrap gap-2 mb-6">
            {project.tags.map(tag => (
              <span
                key={tag}
                className="px-3 py-1 bg-blue-100 text-blue-800 text-sm font-medium rounded"
              >
                {tag}
              </span>
            ))}
          </div>

          {/* Links */}
          <div className="flex flex-wrap gap-4">
            {project.githubUrl && (
              <a href={project.githubUrl} target="_blank" rel="noopener noreferrer">
                <Button variant="primary">Ver no GitHub</Button>
              </a>
            )}
            {project.demoUrl && (
              <a href={project.demoUrl} target="_blank" rel="noopener noreferrer">
                <Button variant="outline">Ver Demo</Button>
              </a>
            )}
          </div>
        </header>

        {/* Content */}
        <div className="prose prose-lg max-w-none">
          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Sobre o Projeto</h2>
            <p className="text-gray-700">{project.description}</p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Tecnologias Utilizadas</h2>
            <ul className="list-disc list-inside text-gray-700 space-y-2">
              {project.tags.map(tag => (
                <li key={tag}>{tag}</li>
              ))}
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Funcionalidades Principais</h2>
            <ul className="list-disc list-inside text-gray-700 space-y-2">
              <li>Arquitetura escalável e componentizada</li>
              <li>Testes automatizados com alta cobertura</li>
              <li>Performance otimizada</li>
              <li>Código limpo e documentado</li>
              <li>Responsivo e acessível</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Aprendizados</h2>
            <p className="text-gray-700">
              Este projeto me permitiu aprofundar conhecimentos em {project.tags.join(', ')} e
              aplicar as melhores práticas de desenvolvimento de software, desde o planejamento até
              o deploy.
            </p>
          </section>

          {project.stargazers_count && (
            <div className="bg-blue-50 rounded-lg p-6 flex items-center gap-4">
              <span className="text-4xl">⭐</span>
              <div>
                <p className="font-bold text-gray-900 text-lg">
                  {project.stargazers_count} stars no GitHub
                </p>
                <p className="text-gray-600">Este projeto tem sido bem recebido pela comunidade</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </article>
  );
}

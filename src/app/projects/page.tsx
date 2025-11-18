// Projects page - Incremental Static Regeneration
import type { Metadata } from 'next';
import Card from '@/components/ui/Card';
import { getProjects } from '@/lib/api';

export const metadata: Metadata = {
  title: 'Projetos',
  description: 'Projetos desenvolvidos por Orlando Fernandes demonstrando habilidades técnicas.',
};

// ISR: revalida a cada 1 hora
export const revalidate = 3600;

export default async function ProjectsPage() {
  const projects = await getProjects();

  return (
    <div className="container mx-auto px-4 py-12">
      <div className="max-w-6xl mx-auto">
        <h1 className="text-4xl md:text-5xl font-bold text-gray-900 mb-4">Meus Projetos</h1>
        <p className="text-xl text-gray-600 mb-12">
          Confira alguns dos projetos que desenvolvi, demonstrando minhas habilidades em
          desenvolvimento web, arquitetura de software e boas práticas.
        </p>

        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6">
          {projects.map(project => (
            <Card
              key={project.id}
              title={project.name}
              description={project.description}
              tags={project.tags}
              href={`/projects/${project.slug}`}
            >
              {project.stargazers_count && (
                <div className="flex items-center gap-2 text-gray-600 text-sm">
                  <span>⭐</span>
                  <span>{project.stargazers_count} stars</span>
                </div>
              )}
            </Card>
          ))}
        </div>

        {projects.length === 0 && (
          <div className="text-center py-12">
            <p className="text-gray-600 text-lg">Nenhum projeto encontrado no momento.</p>
          </div>
        )}
      </div>
    </div>
  );
}

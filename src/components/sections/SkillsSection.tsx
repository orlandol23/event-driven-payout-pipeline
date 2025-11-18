// Skills section para página inicial
export default function SkillsSection() {
  const skills = [
    {
      category: 'Frontend',
      items: ['React', 'Next.js', 'TypeScript', 'Tailwind CSS', 'HTML5/CSS3'],
    },
    {
      category: 'Backend',
      items: ['Node.js', 'Express', 'REST APIs', 'GraphQL', 'PostgreSQL'],
    },
    {
      category: 'DevOps & Ferramentas',
      items: ['Git', 'Docker', 'CI/CD', 'Jest', 'GitHub Actions'],
    },
    {
      category: 'Boas Práticas',
      items: ['TDD', 'Clean Code', 'SOLID', 'Segurança (OWASP)', 'Acessibilidade'],
    },
  ];

  return (
    <section className="py-16 bg-white">
      <div className="container mx-auto px-4">
        <h2 className="text-3xl md:text-4xl font-bold text-center text-gray-900 mb-12">
          Habilidades Técnicas
        </h2>

        <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-8">
          {skills.map(skill => (
            <div key={skill.category} className="bg-gray-50 rounded-lg p-6 hover:shadow-lg transition-shadow">
              <h3 className="text-xl font-bold text-blue-600 mb-4">{skill.category}</h3>
              <ul className="space-y-2">
                {skill.items.map(item => (
                  <li key={item} className="text-gray-700 flex items-center">
                    <span className="text-blue-500 mr-2">✓</span>
                    {item}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// About page - Static Site Generation
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Sobre',
  description:
    'Conheça mais sobre Orlando Fernandes, sua experiência e trajetória profissional.',
};

export default function AboutPage() {
  return (
    <div className="container mx-auto px-4 py-12">
      <div className="max-w-4xl mx-auto">
        <h1 className="text-4xl md:text-5xl font-bold text-gray-900 mb-6">Sobre Mim</h1>

        <div className="prose prose-lg max-w-none">
          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Quem sou eu</h2>
            <p className="text-gray-700 mb-4">
              Sou Orlando Fernandes, um <strong>Senior Software Engineer</strong> com mais de 5
              anos de experiência em desenvolvimento web, especializado em criar aplicações
              modernas, escaláveis e centradas no usuário.
            </p>
            <p className="text-gray-700 mb-4">
              Minha jornada na tecnologia começou com curiosidade sobre como as coisas funcionam na
              web, e desde então tenho me dedicado a dominar as melhores práticas de
              desenvolvimento, desde a arquitetura de software até a experiência do usuário final.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Experiência</h2>
            <div className="space-y-6">
              <div className="border-l-4 border-blue-600 pl-4">
                <h3 className="text-xl font-bold text-gray-900">Senior Software Engineer</h3>
                <p className="text-gray-600 mb-2">Tech Company • 2021 - Presente</p>
                <ul className="list-disc list-inside text-gray-700 space-y-1">
                  <li>
                    Liderança técnica em projetos de larga escala usando React, Next.js e TypeScript
                  </li>
                  <li>Implementação de arquitetura escalável e boas práticas de desenvolvimento</li>
                  <li>Mentoria de desenvolvedores juniores e code reviews</li>
                  <li>Redução de 40% no tempo de carregamento através de otimizações</li>
                </ul>
              </div>

              <div className="border-l-4 border-blue-600 pl-4">
                <h3 className="text-xl font-bold text-gray-900">Full Stack Developer</h3>
                <p className="text-gray-600 mb-2">Startup • 2019 - 2021</p>
                <ul className="list-disc list-inside text-gray-700 space-y-1">
                  <li>Desenvolvimento de aplicações web completas com React e Node.js</li>
                  <li>Implementação de testes automatizados (Jest, Testing Library)</li>
                  <li>Integração de APIs RESTful e GraphQL</li>
                  <li>Deploy e manutenção de aplicações na AWS</li>
                </ul>
              </div>
            </div>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Educação</h2>
            <div className="border-l-4 border-blue-600 pl-4">
              <h3 className="text-xl font-bold text-gray-900">
                Bacharelado em Ciência da Computação
              </h3>
              <p className="text-gray-600">Universidade • 2015 - 2019</p>
            </div>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Valores e Abordagem</h2>
            <ul className="list-disc list-inside text-gray-700 space-y-2">
              <li>
                <strong>Código Limpo:</strong> Acredito que código bem escrito é código que outros
                desenvolvedores conseguem entender e manter facilmente
              </li>
              <li>
                <strong>Testes:</strong> Testes não são opcionais - eles são essenciais para
                garantir qualidade e confiabilidade
              </li>
              <li>
                <strong>Performance:</strong> Cada milissegundo importa na experiência do usuário
              </li>
              <li>
                <strong>Segurança:</strong> Sempre desenvolvimento com segurança em mente, seguindo
                as diretrizes OWASP
              </li>
              <li>
                <strong>Aprendizado Contínuo:</strong> A tecnologia evolui rapidamente, e eu evoluo
                junto
              </li>
            </ul>
          </section>

          <section>
            <h2 className="text-2xl font-bold text-gray-900 mb-4">Interesses</h2>
            <p className="text-gray-700">
              Além de programação, sou apaixonado por design de interfaces, acessibilidade web, e
              sempre busco formas de melhorar a experiência do usuário. Também contribuo com
              projetos open source e escrevo artigos técnicos sobre desenvolvimento web.
            </p>
          </section>
        </div>
      </div>
    </div>
  );
}

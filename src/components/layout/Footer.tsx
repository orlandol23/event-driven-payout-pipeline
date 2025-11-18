// Footer simples com links sociais
export default function Footer() {
  const currentYear = new Date().getFullYear();

  return (
    <footer className="bg-gray-900 text-white py-8 mt-auto">
      <div className="container mx-auto px-4">
        <div className="flex flex-col md:flex-row justify-between items-center gap-4">
          <p className="text-gray-400">
            © {currentYear} Orlando Fernandes. Todos os direitos reservados.
          </p>

          <div className="flex space-x-6">
            <a
              href="https://github.com/orlandol23"
              className="hover:text-blue-400 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="GitHub"
            >
              GitHub
            </a>
            <a
              href="https://linkedin.com/in/orlando-fernandes"
              className="hover:text-blue-400 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="LinkedIn"
            >
              LinkedIn
            </a>
            <a
              href="mailto:orlando@example.com"
              className="hover:text-blue-400 transition-colors"
              aria-label="Email"
            >
              Email
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}

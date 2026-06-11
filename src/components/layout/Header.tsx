// Header com navegação e responsividade
'use client';

import { useState } from 'react';
import Link from 'next/link';
import { NavLink } from '@/types';

export default function Header() {
  const [isMenuOpen, setIsMenuOpen] = useState(false);

  const navLinks: NavLink[] = [
    { href: '/', label: 'Home' },
    { href: '/about', label: 'Sobre' },
    { href: '/projects', label: 'Projetos' },
    { href: '/contact', label: 'Contato' },
  ];

  return (
    <header className="bg-white shadow-sm sticky top-0 z-50">
      <nav className="container mx-auto px-4 py-4">
        <div className="flex justify-between items-center">
          <Link href="/" className="text-2xl font-bold text-blue-600 hover:text-blue-700">
            Orlando Fernandes
          </Link>

          {/* Desktop Navigation */}
          <ul className="hidden md:flex space-x-6">
            {navLinks.map(link => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="text-gray-700 hover:text-blue-600 transition-colors font-medium"
                >
                  {link.label}
                </Link>
              </li>
            ))}
          </ul>

          {/* Mobile Menu Button */}
          <button
            className="md:hidden p-2"
            onClick={() => setIsMenuOpen(!isMenuOpen)}
            aria-label="Toggle menu"
            aria-expanded={isMenuOpen}
          >
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              {isMenuOpen ? (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              ) : (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 6h16M4 12h16M4 18h16"
                />
              )}
            </svg>
          </button>
        </div>

        {/* Mobile Navigation */}
        {isMenuOpen && (
          <ul className="md:hidden mt-4 space-y-2 pb-4">
            {navLinks.map(link => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="block py-2 text-gray-700 hover:text-blue-600 font-medium"
                  onClick={() => setIsMenuOpen(false)}
                >
                  {link.label}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </nav>
    </header>
  );
}

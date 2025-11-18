// Card reutilizável para projetos/posts
import Image from 'next/image';

interface CardProps {
  title: string;
  description: string;
  image?: string;
  tags?: string[];
  href?: string;
  children?: React.ReactNode;
}

export default function Card({
  title,
  description,
  image,
  tags,
  href,
  children,
}: CardProps) {
  return (
    <div className="bg-white rounded-lg shadow-md overflow-hidden hover:shadow-xl transition-shadow duration-300">
      {image && (
        <div className="relative w-full h-48">
          <Image
            src={image}
            alt={title}
            fill
            className="object-cover"
            sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
          />
        </div>
      )}
      <div className="p-6">
        <h3 className="text-xl font-bold mb-2 text-gray-900">{title}</h3>
        <p className="text-gray-600 mb-4 line-clamp-3">{description}</p>

        {tags && tags.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-4">
            {tags.map(tag => (
              <span
                key={tag}
                className="px-2 py-1 bg-blue-100 text-blue-800 text-sm rounded"
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        {children}

        {href && (
          <a
            href={href}
            className="text-blue-600 hover:text-blue-800 hover:underline inline-flex items-center mt-2"
          >
            Ver mais →
          </a>
        )}
      </div>
    </div>
  );
}

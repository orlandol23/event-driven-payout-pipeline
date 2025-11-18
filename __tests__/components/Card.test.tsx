// Testes para Card component
import { render, screen } from '@testing-library/react';
import Card from '@/components/ui/Card';

describe('Card Component', () => {
  it('should render title and description', () => {
    render(<Card title="Test Title" description="Test Description" />);

    expect(screen.getByText('Test Title')).toBeInTheDocument();
    expect(screen.getByText('Test Description')).toBeInTheDocument();
  });

  it('should render tags when provided', () => {
    const tags = ['React', 'TypeScript', 'Next.js'];
    render(<Card title="Test" description="Description" tags={tags} />);

    tags.forEach(tag => {
      expect(screen.getByText(tag)).toBeInTheDocument();
    });
  });

  it('should not render tags section when tags are empty', () => {
    render(<Card title="Test" description="Description" tags={[]} />);

    const card = screen.getByText('Test').closest('div');
    expect(card?.querySelectorAll('.bg-blue-100').length).toBe(0);
  });

  it('should render link when href is provided', () => {
    render(<Card title="Test" description="Description" href="/test-link" />);

    const link = screen.getByText('Ver mais →');
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', '/test-link');
  });

  it('should not render link when href is not provided', () => {
    render(<Card title="Test" description="Description" />);

    expect(screen.queryByText('Ver mais →')).not.toBeInTheDocument();
  });

  it('should render children when provided', () => {
    render(
      <Card title="Test" description="Description">
        <div data-testid="custom-child">Custom Content</div>
      </Card>
    );

    expect(screen.getByTestId('custom-child')).toBeInTheDocument();
    expect(screen.getByText('Custom Content')).toBeInTheDocument();
  });

  it('should apply hover shadow class', () => {
    const { container } = render(<Card title="Test" description="Description" />);

    const card = container.firstChild;
    expect(card).toHaveClass('hover:shadow-xl');
  });

  it('should apply correct styling classes', () => {
    const { container } = render(<Card title="Test" description="Description" />);

    const card = container.firstChild;
    expect(card).toHaveClass('bg-white');
    expect(card).toHaveClass('rounded-lg');
    expect(card).toHaveClass('shadow-md');
  });
});

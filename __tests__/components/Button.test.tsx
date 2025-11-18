// Testes para Button component
import { render, screen, fireEvent } from '@testing-library/react';
import Button from '@/components/ui/Button';

describe('Button Component', () => {
  it('should render with children', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByText('Click me')).toBeInTheDocument();
  });

  it('should apply primary variant by default', () => {
    render(<Button>Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('bg-blue-600');
  });

  it('should apply secondary variant when specified', () => {
    render(<Button variant="secondary">Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('bg-gray-600');
  });

  it('should apply outline variant when specified', () => {
    render(<Button variant="outline">Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('border-2');
    expect(button).toHaveClass('border-blue-600');
  });

  it('should apply small size', () => {
    render(<Button size="sm">Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('px-3');
    expect(button).toHaveClass('py-1.5');
    expect(button).toHaveClass('text-sm');
  });

  it('should apply medium size by default', () => {
    render(<Button>Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('px-4');
    expect(button).toHaveClass('py-2');
    expect(button).toHaveClass('text-base');
  });

  it('should apply large size', () => {
    render(<Button size="lg">Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('px-6');
    expect(button).toHaveClass('py-3');
    expect(button).toHaveClass('text-lg');
  });

  it('should handle onClick event', () => {
    const handleClick = jest.fn();
    render(<Button onClick={handleClick}>Click me</Button>);

    const button = screen.getByRole('button');
    fireEvent.click(button);

    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('should show loading state', () => {
    render(<Button isLoading>Click me</Button>);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('should be disabled when loading', () => {
    render(<Button isLoading>Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
  });

  it('should be disabled when disabled prop is true', () => {
    render(<Button disabled>Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
  });

  it('should apply custom className', () => {
    render(<Button className="custom-class">Click me</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('custom-class');
  });

  it('should not call onClick when disabled', () => {
    const handleClick = jest.fn();
    render(
      <Button onClick={handleClick} disabled>
        Click me
      </Button>
    );

    const button = screen.getByRole('button');
    fireEvent.click(button);

    expect(handleClick).not.toHaveBeenCalled();
  });

  it('should not call onClick when loading', () => {
    const handleClick = jest.fn();
    render(
      <Button onClick={handleClick} isLoading>
        Click me
      </Button>
    );

    const button = screen.getByRole('button');
    fireEvent.click(button);

    expect(handleClick).not.toHaveBeenCalled();
  });
});

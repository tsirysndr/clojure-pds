import { Button } from "@heroui/react";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";

export function MessageScreen({
  title,
  text,
  tone = "danger",
  link,
}: {
  title: string;
  text: string;
  tone?: "danger" | "info";
  link?: { href: string; label: string };
}) {
  return (
    <AuthCard title={title}>
      <Alert tone={tone}>{text}</Alert>

      {link ? (
        <Button as="a" href={link.href} color="primary" size="lg" radius="sm" className="font-medium">
          {link.label}
        </Button>
      ) : null}
    </AuthCard>
  );
}

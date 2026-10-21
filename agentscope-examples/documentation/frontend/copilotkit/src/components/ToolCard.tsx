import type { ReactNode } from "react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { ToolStatus } from "@/copilot/types";

export function ToolCard({
  title,
  status,
  children,
  className,
}: {
  title: string;
  status: ToolStatus;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Card
      className={cn(
        "w-[min(520px,100%)] border-champagne/20 bg-card/90 shadow-[0_18px_48px_oklch(0_0_0/0.35)] backdrop-blur-xl transition-shadow duration-300 hover:shadow-[0_24px_60px_oklch(0_0_0/0.45)]",
        className,
      )}
    >
      <CardHeader className="flex flex-row items-center justify-between gap-3 pb-3">
        <CardTitle className="font-display text-base font-semibold tracking-tight">
          {title}
        </CardTitle>
        <Badge
          variant="secondary"
          className="rounded-full border border-champagne/20 bg-champagne/10 font-semibold text-champagne"
        >
          {status}
        </Badge>
      </CardHeader>
      <CardContent className="pt-0">{children}</CardContent>
    </Card>
  );
}

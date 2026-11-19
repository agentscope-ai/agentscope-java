import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * CopilotKit v2 scopes a Tailwind preflight under [data-copilotkit] that sets
 * `button { background-color: transparent; border-radius: 0 }` with higher
 * specificity than utility classes. Important modifiers keep our shadcn
 * buttons visible inside the provider tree.
 */
const buttonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-xl !rounded-xl text-sm font-semibold transition-all duration-300 outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default:
          "!bg-primary !text-primary-foreground shadow-[0_10px_30px_oklch(0.84_0.065_88/0.18)] hover:!bg-primary/90 hover:shadow-[0_14px_36px_oklch(0.84_0.065_88/0.28)]",
        destructive:
          "!bg-destructive !text-white shadow-xs hover:!bg-destructive/90 focus-visible:ring-destructive/20",
        outline:
          "!border !border-border !bg-secondary/40 !text-foreground shadow-xs hover:!border-champagne/40 hover:!bg-secondary/70",
        secondary:
          "!bg-secondary !text-secondary-foreground shadow-xs hover:!bg-secondary/80 hover:shadow-[0_10px_28px_oklch(0_0_0/0.25)]",
        ghost: "!bg-transparent hover:!bg-accent hover:!text-accent-foreground",
        link: "!bg-transparent !text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: "h-9 !px-4 !py-2",
        sm: "h-8 !rounded-lg !px-3 text-xs",
        lg: "h-11 !rounded-xl !px-6",
        icon: "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

function Button({
  className,
  variant,
  size,
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
  }) {
  const Comp = asChild ? Slot : "button";

  return (
    <Comp
      data-slot="button"
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  );
}

export { Button, buttonVariants };

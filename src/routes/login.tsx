import { zodResolver } from "@hookform/resolvers/zod";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { FlaskConical } from "lucide-react";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ROLE_LABELS } from "@/domain/labels";
import { ROLES, type Role } from "@/domain/types";
import { setDemoOperator, useAppDispatch } from "@/store";

export const Route = createFileRoute("/login")({
  head: () => ({
    meta: [
      { title: "Sign in — SentinelFlow demo console" },
      {
        name: "description",
        content:
          "Demonstration sign-in for the SentinelFlow synthetic fraud-operations console. No real accounts or credentials are used.",
      },
      { property: "og:title", content: "Sign in — SentinelFlow demo console" },
      {
        property: "og:description",
        content: "Demonstration sign-in for a synthetic fraud-operations console.",
      },
    ],
  }),
  component: LoginPage,
});

const schema = z.object({
  operatorId: z
    .string()
    .min(3, "Enter an operator ID of at least 3 characters.")
    .max(40, "Operator ID must be 40 characters or fewer."),
  role: z.enum(ROLES),
});

type FormValues = z.infer<typeof schema>;

function LoginPage() {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { operatorId: "analyst.a1", role: "ANALYST" },
  });

  const role = watch("role");

  const onSubmit = (values: FormValues) => {
    dispatch(setDemoOperator({ operatorId: values.operatorId, role: values.role }));
    void navigate({ to: "/" });
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <main className="w-full max-w-md">
        <div className="rounded-lg border border-border bg-card p-6">
          <h1 className="text-xl font-semibold">SentinelFlow</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Transaction risk &amp; fraud operations console
          </p>

          <p className="mt-4 flex items-start gap-2 rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">
            <FlaskConical aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span>
              Demonstration entry point only. There is no authentication, no credential check, no
              password, no token and nothing stored in the browser. Choosing an operator only
              changes which fictional name and role the prototype displays, and a reload resets it.
              Every screen is reachable directly without coming through here.
            </span>
          </p>

          <form className="mt-6 space-y-5" onSubmit={handleSubmit(onSubmit)} noValidate>
            <div className="space-y-2">
              <Label htmlFor="operatorId">Operator ID</Label>
              <Input
                id="operatorId"
                autoComplete="off"
                aria-invalid={errors.operatorId ? true : undefined}
                aria-describedby={errors.operatorId ? "operatorId-error" : "operatorId-hint"}
                {...register("operatorId")}
              />
              {errors.operatorId ? (
                <p id="operatorId-error" role="alert" className="text-xs text-destructive">
                  {errors.operatorId.message}
                </p>
              ) : (
                <p id="operatorId-hint" className="text-xs text-muted-foreground">
                  Fictional identifier, for example <span className="tabular">analyst.a1</span>.
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="role">Simulated role</Label>
              <Select value={role} onValueChange={(value) => setValue("role", value as Role)}>
                <SelectTrigger id="role" className="w-full">
                  <SelectValue placeholder="Select a role" />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((item) => (
                    <SelectItem key={item} value={item}>
                      {ROLE_LABELS[item]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                Roles change which controls are offered in the interface. They are not a security
                boundary.
              </p>
            </div>

            <Button type="submit" className="w-full" disabled={isSubmitting}>
              Enter demo console
            </Button>
          </form>
        </div>
        <p className="mt-4 text-center text-xs text-muted-foreground">
          Independent educational project. Not affiliated with any bank, financial institution or
          employer.
        </p>
      </main>
    </div>
  );
}

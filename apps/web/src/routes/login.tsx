import { zodResolver } from "@hookform/resolvers/zod";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { FlaskConical } from "lucide-react";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { useLoginMutation } from "@/api/sentinelApi";
import { errorMessage } from "@/components/data-state";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { knownRoles, signedIn, useAppDispatch, useSession } from "@/store";

/**
 * `next` is a path this console will navigate to, so it is checked rather than
 * trusted: anything with a scheme or a protocol-relative `//` prefix would make
 * an open redirect out of the one screen an unauthenticated visitor always
 * reaches.
 */
function safeNext(value: unknown): string {
  if (typeof value !== "string") return "/";
  if (!value.startsWith("/") || value.startsWith("//")) return "/";
  return value;
}

export const Route = createFileRoute("/login")({
  validateSearch: (search: Record<string, unknown>) => ({ next: safeNext(search["next"]) }),
  head: () => ({
    meta: [
      { title: "Sign in — SentinelFlow demo console" },
      {
        name: "description",
        content:
          "Sign-in for the SentinelFlow synthetic fraud-operations console. Demonstration accounts only; no real accounts or credentials are used.",
      },
      { property: "og:title", content: "Sign in — SentinelFlow demo console" },
      {
        property: "og:description",
        content: "Sign-in for a synthetic fraud-operations console.",
      },
    ],
  }),
  component: LoginPage,
});

const schema = z.object({
  username: z
    .string()
    .min(1, "Enter your username.")
    .max(64, "Usernames are 64 characters or fewer."),
  password: z
    .string()
    .min(1, "Enter your password.")
    .max(200, "Passwords are 200 characters or fewer."),
});

type FormValues = z.infer<typeof schema>;

function LoginPage() {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const session = useSession();
  const { next } = Route.useSearch();
  const [login, request] = useLoginMutation();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { username: "", password: "" },
  });

  const onSubmit = async (values: FormValues) => {
    // Not `.unwrap()`: it throws, and a rejection inside a submit handler is an
    // unhandled one. The refusal is already in `request.error`, rendered below.
    const result = await login(values);
    const token = result.data;
    if (!token) return;

    dispatch(
      signedIn({
        username: values.username,
        token: token.token,
        tokenType: token.tokenType,
        expiresAt: token.expiresAt,
        roles: knownRoles(token.roles),
      }),
    );
    await navigate({ to: next });
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
              Demonstration accounts against synthetic data. The token this returns is held in
              memory for the tab and never written to browser storage, so a reload signs you out.
              There is no refresh token: the session ends when the token expires.
            </span>
          </p>

          {session.status === "expired" ? (
            <p
              role="status"
              className="mt-4 rounded-md border border-border bg-surface px-3 py-2 text-xs"
            >
              Your session ended and anything unsaved was not submitted. Sign in to continue.
            </p>
          ) : null}

          <form className="mt-6 space-y-5" onSubmit={handleSubmit(onSubmit)} noValidate>
            <div className="space-y-2">
              <Label htmlFor="username">Username</Label>
              <Input
                id="username"
                autoComplete="username"
                aria-invalid={errors.username ? true : undefined}
                aria-describedby={errors.username ? "username-error" : undefined}
                {...register("username")}
              />
              {errors.username ? (
                <p id="username-error" role="alert" className="text-xs text-destructive">
                  {errors.username.message}
                </p>
              ) : null}
            </div>

            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                aria-invalid={errors.password ? true : undefined}
                aria-describedby={errors.password ? "password-error" : undefined}
                {...register("password")}
              />
              {errors.password ? (
                <p id="password-error" role="alert" className="text-xs text-destructive">
                  {errors.password.message}
                </p>
              ) : null}
            </div>

            {request.isError ? (
              // The API refuses an unknown username, a disabled account and a
              // wrong password identically and on purpose, so this screen has
              // nothing more specific to say and must not invent it.
              <p role="alert" className="text-sm text-destructive">
                {errorMessage(request.error)}
              </p>
            ) : null}

            <Button type="submit" className="w-full" disabled={request.isLoading}>
              {request.isLoading ? "Signing in…" : "Sign in"}
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
